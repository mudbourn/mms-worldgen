use crate::mth::{self, clamp, java_max, java_min};
use crate::tree::{mapped, MappedKind, MarkerKind, Node, Tree};

// One lane-wise step of the cell plan; operands are register numbers.
#[derive(Clone, Copy, Debug)]
enum Op {
    Add(usize, usize, usize),
    Mul(usize, usize, usize),
    Min(usize, usize, usize),
    Max(usize, usize, usize),
    MulOrAdd {
        dst: usize,
        input: usize,
        add: bool,
        argument: f64,
    },
    Mapped {
        dst: usize,
        input: usize,
        kind: MappedKind,
    },
    Clamp {
        dst: usize,
        input: usize,
        min: f64,
        max: f64,
    },
    Range {
        dst: usize,
        input: usize,
        in_range: usize,
        out_of_range: usize,
        min_inclusive: f64,
        max_exclusive: f64,
    },
    Gradient {
        dst: usize,
        from_y: i32,
        to_y: i32,
        from_value: f64,
        to_value: f64,
    },
    Interpolated {
        dst: usize,
        ordinal: usize,
    },
    Noise {
        dst: usize,
        noise: usize,
        xz_scale: f64,
        y_scale: f64,
    },
    Expand(usize, usize),
}

// Block positions of the lanes of a cell and the distinct block ys of its layers.
pub struct Lanes<'a> {
    pub xs: &'a [i32],
    pub ys: &'a [i32],
    pub zs: &'a [i32],
    pub layer_ys: &'a [i32],
    pub layer_of: &'a [usize],
}

// A density function whose cheap nodes run over every block of a cell at once.
pub struct CellPlan {
    ops: Vec<Op>,
    layer_only: Vec<bool>,
    constants: Vec<(usize, f64)>,
    registers: usize,
    root: usize,
}

struct Compiler<'a> {
    tree: &'a Tree,
    ordinals: Vec<usize>,
    reg_of: Vec<Option<usize>>,
    layer_reg: Vec<bool>,
    expanded: Vec<Option<usize>>,
    plan: CellPlan,
}

impl Compiler<'_> {
    fn fresh(&mut self) -> usize {
        self.plan.registers += 1;
        self.layer_reg.push(false);
        self.expanded.push(None);
        self.plan.registers - 1
    }

    fn constant(&mut self, value: f64) -> usize {
        let reg = self.fresh();
        self.layer_reg[reg] = true;
        self.plan.constants.push((reg, value));
        reg
    }

    // The register as a per-lane value, widening a per-layer one on first use.
    fn lane(&mut self, reg: usize) -> usize {
        if !self.layer_reg[reg] || self.plan.constants.iter().any(|(r, _)| *r == reg) {
            return reg;
        }
        if let Some(wide) = self.expanded[reg] {
            return wide;
        }
        let dst = self.fresh();
        self.push(Op::Expand(dst, reg), false);
        self.expanded[reg] = Some(dst);
        dst
    }

    fn push(&mut self, op: Op, layer: bool) {
        self.plan.ops.push(op);
        self.plan.layer_only.push(layer);
    }

    // Emits an op over per-lane operands, or over per-layer ones when all of them are.
    fn emit(&mut self, inputs: &[usize], make: impl Fn(usize, &[usize]) -> Op) -> usize {
        let layer = inputs.iter().all(|r| self.layer_reg[*r]);
        let operands: Vec<usize> = if layer {
            inputs.to_vec()
        } else {
            inputs.iter().map(|r| self.lane(*r)).collect()
        };
        let dst = self.fresh();
        self.layer_reg[dst] = layer;
        self.push(make(dst, &operands), layer);
        dst
    }

    fn visit(&mut self, id: usize) -> Option<usize> {
        if let Some(reg) = self.reg_of[id] {
            return Some(reg);
        }
        let reg = match self.tree.node(id) {
            Node::Constant(v) => self.constant(v),
            Node::BlendAlpha => self.constant(1.0),
            Node::BlendOffset => self.constant(0.0),
            Node::BlendDensity(input) => self.visit(input)?,
            Node::Add(a, b) => {
                let (a, b) = (self.visit(a)?, self.visit(b)?);
                self.emit(&[a, b], |d, r| Op::Add(d, r[0], r[1]))
            }
            Node::Mul(a, b) => {
                let (a, b) = (self.visit(a)?, self.visit(b)?);
                self.emit(&[a, b], |d, r| Op::Mul(d, r[0], r[1]))
            }
            Node::Min(a, b) => {
                let (a, b) = (self.visit(a)?, self.visit(b)?);
                self.emit(&[a, b], |d, r| Op::Min(d, r[0], r[1]))
            }
            Node::Max(a, b) => {
                let (a, b) = (self.visit(a)?, self.visit(b)?);
                self.emit(&[a, b], |d, r| Op::Max(d, r[0], r[1]))
            }
            Node::MulOrAdd {
                input,
                add,
                argument,
            } => {
                let input = self.visit(input)?;
                self.emit(&[input], |dst, r| Op::MulOrAdd {
                    dst,
                    input: r[0],
                    add,
                    argument,
                })
            }
            Node::Mapped { input, kind } => {
                let input = self.visit(input)?;
                self.emit(&[input], |dst, r| Op::Mapped {
                    dst,
                    input: r[0],
                    kind,
                })
            }
            Node::Clamp { input, min, max } => {
                let input = self.visit(input)?;
                self.emit(&[input], |dst, r| Op::Clamp {
                    dst,
                    input: r[0],
                    min,
                    max,
                })
            }
            Node::RangeChoice {
                input,
                in_range,
                out_of_range,
                min_inclusive,
                max_exclusive,
            } => {
                let input = self.visit(input)?;
                let in_range = self.visit(in_range)?;
                let out_of_range = self.visit(out_of_range)?;
                self.emit(&[input, in_range, out_of_range], |dst, r| Op::Range {
                    dst,
                    input: r[0],
                    in_range: r[1],
                    out_of_range: r[2],
                    min_inclusive,
                    max_exclusive,
                })
            }
            Node::YGradient {
                from_y,
                to_y,
                from_value,
                to_value,
            } => {
                let dst = self.fresh();
                self.layer_reg[dst] = true;
                self.push(
                    Op::Gradient {
                        dst,
                        from_y,
                        to_y,
                        from_value,
                        to_value,
                    },
                    true,
                );
                dst
            }
            Node::Noise {
                noise,
                xz_scale,
                y_scale,
            } => {
                let dst = self.fresh();
                self.push(
                    Op::Noise {
                        dst,
                        noise,
                        xz_scale,
                        y_scale,
                    },
                    false,
                );
                dst
            }
            Node::Marker { input, kind } => match kind {
                MarkerKind::Interpolated => {
                    let dst = self.fresh();
                    self.push(
                        Op::Interpolated {
                            dst,
                            ordinal: self.ordinals[id],
                        },
                        false,
                    );
                    dst
                }
                MarkerKind::CacheOnce | MarkerKind::Cache2d => self.visit(input)?,
                MarkerKind::FlatCache | MarkerKind::CacheAllInCell => return None,
            },
            _ => return None,
        };
        self.reg_of[id] = Some(reg);
        Some(reg)
    }
}

// Widens a range by a margin that covers float rounding.
fn pad(range: (f64, f64)) -> (f64, f64) {
    (
        range.0 - (range.0.abs() * 1e-9 + 1e-9),
        range.1 + (range.1.abs() * 1e-9 + 1e-9),
    )
}

fn hull(values: &[f64]) -> (f64, f64) {
    let mut low = f64::INFINITY;
    let mut high = f64::NEG_INFINITY;
    for v in values {
        low = low.min(*v);
        high = high.max(*v);
    }
    (low, high)
}

// The range of a mapped function over an input range, or None when it has a pole inside.
fn mapped_range(kind: MappedKind, input: (f64, f64)) -> Option<(f64, f64)> {
    let (low, high) = input;
    Some(match kind {
        MappedKind::Abs => {
            if low >= 0.0 {
                (low, high)
            } else if high <= 0.0 {
                (-high, -low)
            } else {
                (0.0, (-low).max(high))
            }
        }
        MappedKind::Square => {
            if low >= 0.0 {
                (low * low, high * high)
            } else if high <= 0.0 {
                (high * high, low * low)
            } else {
                (0.0, (low * low).max(high * high))
            }
        }
        MappedKind::Invert => {
            if low <= 0.0 && high >= 0.0 {
                return None;
            }
            hull(&[1.0 / low, 1.0 / high])
        }
        MappedKind::Cube
        | MappedKind::HalfNegative
        | MappedKind::QuarterNegative
        | MappedKind::Squeeze => hull(&[mapped(kind, low), mapped(kind, high)]),
    })
}

fn each(out: &mut [f64], input: &[f64], f: impl Fn(f64) -> f64) {
    for (o, i) in out.iter_mut().zip(input) {
        *o = f(*i);
    }
}

fn each2(out: &mut [f64], a: &[f64], b: &[f64], f: impl Fn(f64, f64) -> f64) {
    for ((o, x), y) in out.iter_mut().zip(a).zip(b) {
        *o = f(*x, *y);
    }
}

impl CellPlan {
    // Compiles the nodes under a root, or None when one of them needs the scalar path.
    pub fn compile(tree: &Tree, root: usize) -> Option<CellPlan> {
        let mut ordinals = vec![usize::MAX; tree.node_count()];
        let mut next = 0;
        for (id, _, kind) in tree.markers() {
            if kind == MarkerKind::Interpolated {
                ordinals[id] = next;
                next += 1;
            }
        }
        let mut compiler = Compiler {
            tree,
            ordinals,
            reg_of: vec![None; tree.node_count()],
            layer_reg: Vec::new(),
            expanded: Vec::new(),
            plan: CellPlan {
                ops: Vec::new(),
                layer_only: Vec::new(),
                constants: Vec::new(),
                registers: 0,
                root: 0,
            },
        };
        compiler.plan.root = compiler.visit(root)?;
        Some(compiler.plan)
    }

    // Bounds the root over a cell from the corner ranges, or None when the bounds are not trustworthy.
    pub fn root_range(
        &self,
        tree: &Tree,
        bounds: &mut Vec<(f64, f64)>,
        corners: &[(f64, f64)],
        y_low: i32,
        y_high: i32,
    ) -> Option<(f64, f64)> {
        bounds.clear();
        bounds.resize(self.registers, (0.0, 0.0));
        for (reg, value) in &self.constants {
            bounds[*reg] = (*value, *value);
        }
        for op in &self.ops {
            let (dst, range) = match *op {
                Op::Add(d, a, b) => (d, (bounds[a].0 + bounds[b].0, bounds[a].1 + bounds[b].1)),
                Op::Mul(d, a, b) => {
                    let (a, b) = (bounds[a], bounds[b]);
                    let products = [a.0 * b.0, a.0 * b.1, a.1 * b.0, a.1 * b.1];
                    (d, hull(&products))
                }
                Op::Min(d, a, b) => (
                    d,
                    (bounds[a].0.min(bounds[b].0), bounds[a].1.min(bounds[b].1)),
                ),
                Op::Max(d, a, b) => (
                    d,
                    (bounds[a].0.max(bounds[b].0), bounds[a].1.max(bounds[b].1)),
                ),
                Op::MulOrAdd {
                    dst,
                    input,
                    add,
                    argument,
                } => {
                    let i = bounds[input];
                    if add {
                        (dst, (i.0 + argument, i.1 + argument))
                    } else {
                        (dst, hull(&[i.0 * argument, i.1 * argument]))
                    }
                }
                Op::Mapped { dst, input, kind } => (dst, mapped_range(kind, bounds[input])?),
                Op::Clamp {
                    dst,
                    input,
                    min,
                    max,
                } => {
                    let i = bounds[input];
                    (dst, (clamp(i.0, min, max), clamp(i.1, min, max)))
                }
                Op::Range {
                    dst,
                    input,
                    in_range,
                    out_of_range,
                    min_inclusive,
                    max_exclusive,
                } => {
                    let i = bounds[input];
                    let (a, b) = (bounds[in_range], bounds[out_of_range]);
                    let range = if i.0 >= min_inclusive && i.1 < max_exclusive {
                        a
                    } else if i.1 < min_inclusive || i.0 >= max_exclusive {
                        b
                    } else {
                        (a.0.min(b.0), a.1.max(b.1))
                    };
                    (dst, range)
                }
                Op::Gradient {
                    dst,
                    from_y,
                    to_y,
                    from_value,
                    to_value,
                } => {
                    let at = |y: i32| {
                        let t = (y as f64 - from_y as f64) / (to_y as f64 - from_y as f64);
                        mth::clamped_lerp(t, from_value, to_value)
                    };
                    (dst, hull(&[at(y_low), at(y_high)]))
                }
                Op::Interpolated { dst, ordinal } => (dst, corners[ordinal]),
                Op::Noise { dst, noise, .. } => {
                    let max = tree.noise_max(noise);
                    (dst, (-max, max))
                }
                Op::Expand(dst, src) => (dst, bounds[src]),
            };
            if !range.0.is_finite() || !range.1.is_finite() {
                return None;
            }
            bounds[dst] = pad(range);
        }
        Some(bounds[self.root])
    }

    pub fn register_count(&self) -> usize {
        self.registers
    }

    pub fn root(&self) -> usize {
        self.root
    }

    // Writes the constant registers once for a lane count.
    pub fn init(&self, regs: &mut [f64], lanes: usize) {
        for (reg, value) in &self.constants {
            regs[reg * lanes..(reg + 1) * lanes].fill(*value);
        }
    }

    // Runs every step over all lanes, or over the layers for steps that depend on y alone.
    pub fn run(
        &self,
        tree: &Tree,
        regs: &mut [f64],
        stride: usize,
        at: &Lanes,
        cells: &[Vec<f64>],
    ) {
        for (op, layer) in self.ops.iter().zip(&self.layer_only) {
            let (lanes, ys) = if *layer {
                (at.layer_ys.len(), at.layer_ys)
            } else {
                (at.ys.len(), at.ys)
            };
            let dst = match *op {
                Op::Add(d, ..) | Op::Mul(d, ..) | Op::Min(d, ..) | Op::Max(d, ..) => d,
                Op::MulOrAdd { dst, .. }
                | Op::Mapped { dst, .. }
                | Op::Clamp { dst, .. }
                | Op::Range { dst, .. }
                | Op::Gradient { dst, .. }
                | Op::Interpolated { dst, .. }
                | Op::Noise { dst, .. } => dst,
                Op::Expand(dst, _) => dst,
            };
            let (before, after) = regs.split_at_mut(dst * stride);
            let out = &mut after[..lanes];
            let reg = |r: usize| &before[r * stride..r * stride + lanes];
            match *op {
                Op::Add(_, a, b) => each2(out, reg(a), reg(b), |x, y| x + y),
                Op::Mul(_, a, b) => each2(
                    out,
                    reg(a),
                    reg(b),
                    |x, y| {
                        if x == 0.0 {
                            0.0
                        } else {
                            x * y
                        }
                    },
                ),
                Op::Min(_, a, b) => each2(out, reg(a), reg(b), java_min),
                Op::Max(_, a, b) => each2(out, reg(a), reg(b), java_max),
                Op::MulOrAdd {
                    input,
                    add,
                    argument,
                    ..
                } => {
                    if add {
                        each(out, reg(input), |d| d + argument);
                    } else {
                        each(out, reg(input), |d| d * argument);
                    }
                }
                Op::Mapped { input, kind, .. } => {
                    let src = reg(input);
                    match kind {
                        MappedKind::Abs => each(out, src, |d| mapped(MappedKind::Abs, d)),
                        MappedKind::Square => each(out, src, |d| mapped(MappedKind::Square, d)),
                        MappedKind::Cube => each(out, src, |d| mapped(MappedKind::Cube, d)),
                        MappedKind::HalfNegative => {
                            each(out, src, |d| mapped(MappedKind::HalfNegative, d))
                        }
                        MappedKind::QuarterNegative => {
                            each(out, src, |d| mapped(MappedKind::QuarterNegative, d))
                        }
                        MappedKind::Invert => each(out, src, |d| mapped(MappedKind::Invert, d)),
                        MappedKind::Squeeze => each(out, src, |d| mapped(MappedKind::Squeeze, d)),
                    }
                }
                Op::Clamp {
                    input, min, max, ..
                } => each(out, reg(input), |d| clamp(d, min, max)),
                Op::Range {
                    input,
                    in_range,
                    out_of_range,
                    min_inclusive,
                    max_exclusive,
                    ..
                } => {
                    let (i, a, b) = (reg(input), reg(in_range), reg(out_of_range));
                    for l in 0..lanes {
                        let d = i[l];
                        out[l] = if d >= min_inclusive && d < max_exclusive {
                            a[l]
                        } else {
                            b[l]
                        };
                    }
                }
                Op::Gradient {
                    from_y,
                    to_y,
                    from_value,
                    to_value,
                    ..
                } => {
                    for (o, y) in out.iter_mut().zip(ys) {
                        let t = (*y as f64 - from_y as f64) / (to_y as f64 - from_y as f64);
                        *o = mth::clamped_lerp(t, from_value, to_value);
                    }
                }
                Op::Interpolated { ordinal, .. } => out.copy_from_slice(&cells[ordinal]),
                Op::Noise {
                    noise,
                    xz_scale,
                    y_scale,
                    ..
                } => {
                    for (l, o) in out.iter_mut().enumerate() {
                        *o = tree.noise_value(
                            noise,
                            at.xs[l] as f64 * xz_scale,
                            at.ys[l] as f64 * y_scale,
                            at.zs[l] as f64 * xz_scale,
                        );
                    }
                }
                Op::Expand(_, src) => {
                    let layers = &before[src * stride..];
                    for (o, layer) in out.iter_mut().zip(at.layer_of) {
                        *o = layers[*layer];
                    }
                }
            }
        }
    }
}
