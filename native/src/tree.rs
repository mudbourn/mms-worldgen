use crate::mth::{self, clamp, java_max, java_min};
use crate::noise::{BlendedNoise, NormalNoise};
use crate::random::{
    LegacyRandomSource, PositionalRandomFactory, RandomSource, XoroshiroRandomSource,
};

pub const CONSTANT: i32 = 0;
pub const ADD: i32 = 1;
pub const MUL: i32 = 2;
pub const MIN: i32 = 3;
pub const MAX: i32 = 4;
pub const MUL_OR_ADD: i32 = 5;
pub const MAPPED: i32 = 6;
pub const CLAMP: i32 = 7;
pub const MARKER: i32 = 8;
pub const NOISE: i32 = 9;
pub const SHIFT_A: i32 = 10;
pub const SHIFT_B: i32 = 11;
pub const SHIFT: i32 = 12;
pub const SHIFTED_NOISE: i32 = 13;
pub const WEIRD_SCALED: i32 = 14;
pub const RANGE_CHOICE: i32 = 15;
pub const Y_GRADIENT: i32 = 16;
pub const FIND_TOP_SURFACE: i32 = 17;
pub const BLEND_ALPHA: i32 = 18;
pub const BLEND_OFFSET: i32 = 19;
pub const BLEND_DENSITY: i32 = 20;
pub const BEARDIFIER: i32 = 21;
pub const BLENDED_NOISE: i32 = 22;
pub const SPLINE: i32 = 23;

pub const NODE_INTS: usize = 5;
pub const NODE_DOUBLES: usize = 2;
pub const SPLINE_INTS: usize = 4;
pub const BLENDED_DOUBLES: usize = 5;

// Flat description of a density function tree as sent from Java.
#[derive(Default)]
pub struct Spec {
    pub seed: i64,
    pub legacy: bool,
    pub node_ints: Vec<i32>,
    pub node_doubles: Vec<f64>,
    pub noise_keys: Vec<String>,
    pub noise_octaves: Vec<i32>,
    pub noise_amp_ends: Vec<i32>,
    pub noise_amps: Vec<f64>,
    pub blended: Vec<f64>,
    pub spline_ints: Vec<i32>,
    pub spline_consts: Vec<f64>,
    pub point_children: Vec<i32>,
    pub point_values: Vec<f64>,
    pub roots: Vec<i32>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum MarkerKind {
    Interpolated,
    FlatCache,
    Cache2d,
    CacheOnce,
    CacheAllInCell,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum MappedKind {
    Abs,
    Square,
    Cube,
    HalfNegative,
    QuarterNegative,
    Invert,
    Squeeze,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Node {
    Constant(f64),
    Add(usize, usize),
    Mul(usize, usize),
    Min(usize, usize),
    Max(usize, usize),
    MulOrAdd {
        input: usize,
        add: bool,
        argument: f64,
    },
    Mapped {
        input: usize,
        kind: MappedKind,
    },
    Clamp {
        input: usize,
        min: f64,
        max: f64,
    },
    Marker {
        input: usize,
        kind: MarkerKind,
    },
    Noise {
        noise: usize,
        xz_scale: f64,
        y_scale: f64,
    },
    ShiftA(usize),
    ShiftB(usize),
    Shift(usize),
    ShiftedNoise {
        shift_x: usize,
        shift_y: usize,
        shift_z: usize,
        noise: usize,
        xz_scale: f64,
        y_scale: f64,
    },
    WeirdScaled {
        input: usize,
        noise: usize,
        type2: bool,
    },
    RangeChoice {
        input: usize,
        in_range: usize,
        out_of_range: usize,
        min_inclusive: f64,
        max_exclusive: f64,
    },
    YGradient {
        from_y: i32,
        to_y: i32,
        from_value: f64,
        to_value: f64,
    },
    FindTopSurface {
        density: usize,
        upper_bound: usize,
        lower_bound: i32,
        cell_height: i32,
    },
    BlendAlpha,
    BlendOffset,
    BlendDensity(usize),
    Beardifier,
    BlendedNoise(usize),
    Spline(usize),
}

enum Spline {
    Constant(f32),
    Multipoint {
        coordinate: usize,
        locations: Vec<f32>,
        derivatives: Vec<f32>,
        values: Vec<usize>,
    },
}

// Callbacks that let a chunk evaluator replace marker and beardifier nodes.
pub trait Hooks {
    #[allow(clippy::too_many_arguments)]
    fn marker(
        &mut self,
        tree: &Tree,
        id: usize,
        input: usize,
        kind: MarkerKind,
        x: i32,
        y: i32,
        z: i32,
    ) -> f64;

    fn beardifier(&mut self, x: i32, y: i32, z: i32) -> f64;
}

struct Plain;

impl Hooks for Plain {
    fn marker(
        &mut self,
        tree: &Tree,
        _id: usize,
        input: usize,
        _kind: MarkerKind,
        x: i32,
        y: i32,
        z: i32,
    ) -> f64 {
        tree.compute_with(input, x, y, z, self)
    }

    fn beardifier(&mut self, _x: i32, _y: i32, _z: i32) -> f64 {
        0.0
    }
}

// Evaluable density function tree with noises wired from a world seed.
pub struct Tree {
    nodes: Vec<Node>,
    noises: Vec<NormalNoise>,
    blended: Vec<BlendedNoise>,
    splines: Vec<Spline>,
    roots: Vec<usize>,
}

fn lerp_f32(f: f32, g: f32, h: f32) -> f32 {
    g + f * (h - g)
}

fn linear_extend(f: f32, locations: &[f32], g: f32, derivatives: &[f32], i: usize) -> f32 {
    let h = derivatives[i];
    if h == 0.0 {
        g
    } else {
        g + h * (f - locations[i])
    }
}

fn find_interval_start(locations: &[f32], f: f32) -> i32 {
    let mut i = 0i32;
    let mut k = locations.len() as i32;
    while k > 0 {
        let l = k / 2;
        let m = i + l;
        if f < locations[m as usize] {
            k = l;
        } else {
            i = m + 1;
            k -= l + 1;
        }
    }
    i - 1
}

fn mapped(kind: MappedKind, d: f64) -> f64 {
    match kind {
        MappedKind::Abs => d.abs(),
        MappedKind::Square => d * d,
        MappedKind::Cube => d * d * d,
        MappedKind::HalfNegative => {
            if d > 0.0 {
                d
            } else {
                d * 0.5
            }
        }
        MappedKind::QuarterNegative => {
            if d > 0.0 {
                d
            } else {
                d * 0.25
            }
        }
        MappedKind::Invert => 1.0 / d,
        MappedKind::Squeeze => {
            let e = clamp(d, -1.0, 1.0);
            e / 2.0 - e * e * e / 24.0
        }
    }
}

fn rarity(type2: bool, d: f64) -> f64 {
    if type2 {
        if d < -0.75 {
            0.5
        } else if d < -0.5 {
            0.75
        } else if d < 0.5 {
            1.0
        } else if d < 0.75 {
            2.0
        } else {
            3.0
        }
    } else if d < -0.5 {
        0.75
    } else if d < 0.0 {
        1.0
    } else if d < 0.5 {
        1.5
    } else {
        2.0
    }
}

fn index(value: i32, limit: usize, what: &str) -> Result<usize, String> {
    if value >= 0 && (value as usize) < limit {
        Ok(value as usize)
    } else {
        Err(format!("{what} index {value} out of range {limit}"))
    }
}

enum Factory {
    Legacy(<LegacyRandomSource as RandomSource>::Factory),
    Xoroshiro(<XoroshiroRandomSource as RandomSource>::Factory),
}

fn build_noise(
    seed: i64,
    legacy: bool,
    factory: &Factory,
    key: &str,
    first_octave: i32,
    amplitudes: &[f64],
) -> Result<NormalNoise, String> {
    let failed = || format!("legacy noise {key} could not be built");
    if legacy {
        match key {
            "minecraft:temperature" => {
                return NormalNoise::create_legacy_nether_biome(
                    &mut LegacyRandomSource::new(seed),
                    -7,
                    &[1.0, 1.0],
                )
                .ok_or_else(failed);
            }
            "minecraft:vegetation" => {
                return NormalNoise::create_legacy_nether_biome(
                    &mut LegacyRandomSource::new(seed.wrapping_add(1)),
                    -7,
                    &[1.0, 1.0],
                )
                .ok_or_else(failed);
            }
            "minecraft:offset" => {
                if let Factory::Legacy(factory) = factory {
                    return Ok(NormalNoise::create(
                        &mut factory.from_hash_of(key),
                        0,
                        &[0.0],
                    ));
                }
            }
            _ => {}
        }
    }

    Ok(match factory {
        Factory::Legacy(factory) => {
            NormalNoise::create(&mut factory.from_hash_of(key), first_octave, amplitudes)
        }
        Factory::Xoroshiro(factory) => {
            NormalNoise::create(&mut factory.from_hash_of(key), first_octave, amplitudes)
        }
    })
}

fn build_blended(
    seed: i64,
    legacy: bool,
    factory: &Factory,
    p: &[f64],
) -> Result<BlendedNoise, String> {
    let failed = || "blended noise could not be built".to_string();
    let (xz_scale, y_scale, xz_factor, y_factor, smear) = (p[0], p[1], p[2], p[3], p[4]);
    match factory {
        Factory::Legacy(_) if legacy => BlendedNoise::new(
            &mut LegacyRandomSource::new(seed),
            xz_scale,
            y_scale,
            xz_factor,
            y_factor,
            smear,
        ),
        Factory::Legacy(factory) => BlendedNoise::new(
            &mut factory.from_hash_of("minecraft:terrain"),
            xz_scale,
            y_scale,
            xz_factor,
            y_factor,
            smear,
        ),
        Factory::Xoroshiro(factory) => BlendedNoise::new(
            &mut factory.from_hash_of("minecraft:terrain"),
            xz_scale,
            y_scale,
            xz_factor,
            y_factor,
            smear,
        ),
    }
    .ok_or_else(failed)
}

impl Tree {
    // Builds the tree from its flat description, wiring noises from the seed.
    pub fn build(spec: &Spec) -> Result<Tree, String> {
        let factory = if spec.legacy {
            Factory::Legacy(LegacyRandomSource::new(spec.seed).fork_positional())
        } else {
            Factory::Xoroshiro(XoroshiroRandomSource::new(spec.seed).fork_positional())
        };

        let noise_count = spec.noise_keys.len();
        if spec.noise_octaves.len() != noise_count || spec.noise_amp_ends.len() != noise_count {
            return Err("noise tables differ in length".to_string());
        }
        let mut noises = Vec::with_capacity(noise_count);
        let mut start = 0usize;
        for (i, key) in spec.noise_keys.iter().enumerate() {
            let end = index(
                spec.noise_amp_ends[i],
                spec.noise_amps.len() + 1,
                "amplitude",
            )?;
            if end < start {
                return Err("noise amplitude ends decrease".to_string());
            }
            noises.push(build_noise(
                spec.seed,
                spec.legacy,
                &factory,
                key,
                spec.noise_octaves[i],
                &spec.noise_amps[start..end],
            )?);
            start = end;
        }

        let mut blended = Vec::new();
        for p in spec.blended.chunks_exact(BLENDED_DOUBLES) {
            blended.push(build_blended(spec.seed, spec.legacy, &factory, p)?);
        }

        let spline_count = spec.spline_ints.len() / SPLINE_INTS;
        let node_count = spec.node_ints.len() / NODE_INTS;
        let mut splines = Vec::with_capacity(spline_count);
        for (i, s) in spec.spline_ints.chunks_exact(SPLINE_INTS).enumerate() {
            if s[0] == 0 {
                let value = *spec.spline_consts.get(i).ok_or("spline constant missing")?;
                splines.push(Spline::Constant(value as f32));
                continue;
            }
            let count = index(s[2], usize::MAX, "point count")?;
            let first = index(s[3], usize::MAX, "point start")?;
            let mut locations = Vec::with_capacity(count);
            let mut derivatives = Vec::with_capacity(count);
            let mut values = Vec::with_capacity(count);
            for p in first..first + count {
                let child = *spec.point_children.get(p).ok_or("spline point missing")?;
                values.push(index(child, spline_count, "spline child")?);
                locations.push(*spec.point_values.get(p * 2).ok_or("spline point missing")? as f32);
                derivatives.push(
                    *spec
                        .point_values
                        .get(p * 2 + 1)
                        .ok_or("spline point missing")? as f32,
                );
            }
            if count == 0 {
                return Err("spline without points".to_string());
            }
            splines.push(Spline::Multipoint {
                coordinate: index(s[1], node_count, "spline coordinate")?,
                locations,
                derivatives,
                values,
            });
        }

        let mut nodes = Vec::with_capacity(node_count);
        for (i, n) in spec.node_ints.chunks_exact(NODE_INTS).enumerate() {
            let d = &spec.node_doubles[i * NODE_DOUBLES..(i + 1) * NODE_DOUBLES];
            let child = |v: i32| index(v, i, "child");
            let noise = |v: i32| index(v, noises.len(), "noise");
            let node = match n[0] {
                CONSTANT => Node::Constant(d[0]),
                ADD => Node::Add(child(n[1])?, child(n[2])?),
                MUL => Node::Mul(child(n[1])?, child(n[2])?),
                MIN => Node::Min(child(n[1])?, child(n[2])?),
                MAX => Node::Max(child(n[1])?, child(n[2])?),
                MUL_OR_ADD => Node::MulOrAdd {
                    input: child(n[1])?,
                    add: n[2] != 0,
                    argument: d[0],
                },
                MAPPED => Node::Mapped {
                    input: child(n[1])?,
                    kind: match n[2] {
                        0 => MappedKind::Abs,
                        1 => MappedKind::Square,
                        2 => MappedKind::Cube,
                        3 => MappedKind::HalfNegative,
                        4 => MappedKind::QuarterNegative,
                        5 => MappedKind::Invert,
                        6 => MappedKind::Squeeze,
                        other => return Err(format!("mapped kind {other}")),
                    },
                },
                CLAMP => Node::Clamp {
                    input: child(n[1])?,
                    min: d[0],
                    max: d[1],
                },
                MARKER => Node::Marker {
                    input: child(n[1])?,
                    kind: match n[2] {
                        0 => MarkerKind::Interpolated,
                        1 => MarkerKind::FlatCache,
                        2 => MarkerKind::Cache2d,
                        3 => MarkerKind::CacheOnce,
                        4 => MarkerKind::CacheAllInCell,
                        other => return Err(format!("marker kind {other}")),
                    },
                },
                NOISE => Node::Noise {
                    noise: noise(n[1])?,
                    xz_scale: d[0],
                    y_scale: d[1],
                },
                SHIFT_A => Node::ShiftA(noise(n[1])?),
                SHIFT_B => Node::ShiftB(noise(n[1])?),
                SHIFT => Node::Shift(noise(n[1])?),
                SHIFTED_NOISE => Node::ShiftedNoise {
                    shift_x: child(n[1])?,
                    shift_y: child(n[2])?,
                    shift_z: child(n[3])?,
                    noise: noise(n[4])?,
                    xz_scale: d[0],
                    y_scale: d[1],
                },
                WEIRD_SCALED => Node::WeirdScaled {
                    input: child(n[1])?,
                    noise: noise(n[2])?,
                    type2: n[3] != 0,
                },
                RANGE_CHOICE => Node::RangeChoice {
                    input: child(n[1])?,
                    in_range: child(n[2])?,
                    out_of_range: child(n[3])?,
                    min_inclusive: d[0],
                    max_exclusive: d[1],
                },
                Y_GRADIENT => Node::YGradient {
                    from_y: n[1],
                    to_y: n[2],
                    from_value: d[0],
                    to_value: d[1],
                },
                FIND_TOP_SURFACE => Node::FindTopSurface {
                    density: child(n[1])?,
                    upper_bound: child(n[2])?,
                    lower_bound: n[3],
                    cell_height: n[4],
                },
                BLEND_ALPHA => Node::BlendAlpha,
                BLEND_OFFSET => Node::BlendOffset,
                BLEND_DENSITY => Node::BlendDensity(child(n[1])?),
                BEARDIFIER => Node::Beardifier,
                BLENDED_NOISE => Node::BlendedNoise(index(n[1], blended.len(), "blended")?),
                SPLINE => Node::Spline(index(n[1], splines.len(), "spline")?),
                other => return Err(format!("unknown opcode {other}")),
            };
            nodes.push(node);
        }

        let mut roots = Vec::with_capacity(spec.roots.len());
        for r in &spec.roots {
            roots.push(index(*r, nodes.len(), "root")?);
        }

        Ok(Tree {
            nodes,
            noises,
            blended,
            splines,
            roots,
        })
    }

    pub fn root_count(&self) -> usize {
        self.roots.len()
    }

    // Evaluates a router field at one block position.
    pub fn compute_root(&self, root: usize, x: i32, y: i32, z: i32) -> Option<f64> {
        self.roots.get(root).map(|r| self.compute(*r, x, y, z))
    }

    pub fn compute(&self, id: usize, x: i32, y: i32, z: i32) -> f64 {
        self.compute_with(id, x, y, z, &mut Plain)
    }

    // Returns the node id of a router field.
    pub fn root_node(&self, root: usize) -> usize {
        self.roots[root]
    }

    // Lists every marker node as node id, wrapped node id and kind.
    pub fn markers(&self) -> Vec<(usize, usize, MarkerKind)> {
        self.nodes
            .iter()
            .enumerate()
            .filter_map(|(id, node)| match node {
                Node::Marker { input, kind } => Some((id, *input, *kind)),
                _ => None,
            })
            .collect()
    }

    pub fn node_count(&self) -> usize {
        self.nodes.len()
    }

    pub fn compute_with<H: Hooks>(&self, id: usize, x: i32, y: i32, z: i32, h: &mut H) -> f64 {
        match self.nodes[id] {
            Node::Constant(v) => v,
            Node::Add(a, b) => self.compute_with(a, x, y, z, h) + self.compute_with(b, x, y, z, h),
            Node::Mul(a, b) => {
                let d = self.compute_with(a, x, y, z, h);
                if d == 0.0 {
                    0.0
                } else {
                    d * self.compute_with(b, x, y, z, h)
                }
            }
            Node::Min(a, b) => java_min(
                self.compute_with(a, x, y, z, h),
                self.compute_with(b, x, y, z, h),
            ),
            Node::Max(a, b) => java_max(
                self.compute_with(a, x, y, z, h),
                self.compute_with(b, x, y, z, h),
            ),
            Node::MulOrAdd {
                input,
                add,
                argument,
            } => {
                let d = self.compute_with(input, x, y, z, h);
                if add {
                    d + argument
                } else {
                    d * argument
                }
            }
            Node::Mapped { input, kind } => mapped(kind, self.compute_with(input, x, y, z, h)),
            Node::Clamp { input, min, max } => {
                clamp(self.compute_with(input, x, y, z, h), min, max)
            }
            Node::Marker { input, kind } => h.marker(self, id, input, kind, x, y, z),
            Node::Noise {
                noise,
                xz_scale,
                y_scale,
            } => self.noises[noise].get_value(
                x as f64 * xz_scale,
                y as f64 * y_scale,
                z as f64 * xz_scale,
            ),
            Node::ShiftA(noise) => self.shift(noise, x as f64, 0.0, z as f64),
            Node::ShiftB(noise) => self.shift(noise, z as f64, x as f64, 0.0),
            Node::Shift(noise) => self.shift(noise, x as f64, y as f64, z as f64),
            Node::ShiftedNoise {
                shift_x,
                shift_y,
                shift_z,
                noise,
                xz_scale,
                y_scale,
            } => {
                let d = x as f64 * xz_scale + self.compute_with(shift_x, x, y, z, h);
                let e = y as f64 * y_scale + self.compute_with(shift_y, x, y, z, h);
                let f = z as f64 * xz_scale + self.compute_with(shift_z, x, y, z, h);
                self.noises[noise].get_value(d, e, f)
            }
            Node::WeirdScaled {
                input,
                noise,
                type2,
            } => {
                let e = rarity(type2, self.compute_with(input, x, y, z, h));
                e * self.noises[noise]
                    .get_value(x as f64 / e, y as f64 / e, z as f64 / e)
                    .abs()
            }
            Node::RangeChoice {
                input,
                in_range,
                out_of_range,
                min_inclusive,
                max_exclusive,
            } => {
                let d = self.compute_with(input, x, y, z, h);
                if d >= min_inclusive && d < max_exclusive {
                    self.compute_with(in_range, x, y, z, h)
                } else {
                    self.compute_with(out_of_range, x, y, z, h)
                }
            }
            Node::YGradient {
                from_y,
                to_y,
                from_value,
                to_value,
            } => {
                let t = (y as f64 - from_y as f64) / (to_y as f64 - from_y as f64);
                mth::clamped_lerp(t, from_value, to_value)
            }
            Node::FindTopSurface {
                density,
                upper_bound,
                lower_bound,
                cell_height,
            } => {
                let i = mth::floor(self.compute_with(upper_bound, x, y, z, h) / cell_height as f64)
                    .wrapping_mul(cell_height);
                if i <= lower_bound {
                    return lower_bound as f64;
                }
                let mut j = i;
                while j >= lower_bound {
                    if self.compute_with(density, x, j, z, h) > 0.0 {
                        return j as f64;
                    }
                    j = j.wrapping_sub(cell_height);
                }
                lower_bound as f64
            }
            Node::BlendAlpha => 1.0,
            Node::BlendOffset => 0.0,
            Node::Beardifier => h.beardifier(x, y, z),
            Node::BlendDensity(input) => self.compute_with(input, x, y, z, h),
            Node::BlendedNoise(blended) => self.blended[blended].compute(x, y, z),
            Node::Spline(spline) => self.spline(spline, x, y, z, h) as f64,
        }
    }

    fn shift(&self, noise: usize, d: f64, e: f64, f: f64) -> f64 {
        self.noises[noise].get_value(d * 0.25, e * 0.25, f * 0.25) * 4.0
    }

    fn spline<H: Hooks>(&self, id: usize, x: i32, y: i32, z: i32, hk: &mut H) -> f32 {
        match &self.splines[id] {
            Spline::Constant(v) => *v,
            Spline::Multipoint {
                coordinate,
                locations,
                derivatives,
                values,
            } => {
                let f = self.compute_with(*coordinate, x, y, z, hk) as f32;
                let i = find_interval_start(locations, f);
                let j = locations.len() - 1;
                if i < 0 {
                    let v = self.spline(values[0], x, y, z, hk);
                    return linear_extend(f, locations, v, derivatives, 0);
                }
                let i = i as usize;
                if i == j {
                    let v = self.spline(values[j], x, y, z, hk);
                    return linear_extend(f, locations, v, derivatives, j);
                }
                let g = locations[i];
                let h = locations[i + 1];
                let k = (f - g) / (h - g);
                let l = derivatives[i];
                let m = derivatives[i + 1];
                let n = self.spline(values[i], x, y, z, hk);
                let o = self.spline(values[i + 1], x, y, z, hk);
                let p = l * (h - g) - (o - n);
                let q = -m * (h - g) + (o - n);
                lerp_f32(k, n, o) + k * (1.0 - k) * lerp_f32(k, p, q)
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn node(spec: &mut Spec, ints: [i32; NODE_INTS], doubles: [f64; NODE_DOUBLES]) -> i32 {
        spec.node_ints.extend(ints);
        spec.node_doubles.extend(doubles);
        (spec.node_ints.len() / NODE_INTS) as i32 - 1
    }

    fn constant(spec: &mut Spec, value: f64) -> i32 {
        node(spec, [CONSTANT, 0, 0, 0, 0], [value, 0.0])
    }

    fn y_gradient(spec: &mut Spec) -> i32 {
        node(spec, [Y_GRADIENT, 0, 10, 0, 0], [0.0, 100.0])
    }

    fn eval(mut spec: Spec, root: i32, x: i32, y: i32, z: i32) -> f64 {
        spec.roots = vec![root];
        Tree::build(&spec)
            .unwrap()
            .compute_root(0, x, y, z)
            .unwrap()
    }

    #[test]
    fn y_gradient_clamps_and_interpolates() {
        let at = |y| {
            let mut spec = Spec::default();
            let g = y_gradient(&mut spec);
            eval(spec, g, 0, y, 0)
        };
        assert_eq!(at(-5), 0.0);
        assert_eq!(at(5), 50.0);
        assert_eq!(at(40), 100.0);
    }

    #[test]
    fn mul_short_circuits_zero_and_add_sums() {
        let mut spec = Spec::default();
        let zero = constant(&mut spec, -0.0);
        let g = y_gradient(&mut spec);
        let mul = node(&mut spec, [MUL, zero, g, 0, 0], [0.0, 0.0]);
        let value = eval(spec, mul, 0, 5, 0);
        assert_eq!(value.to_bits(), 0.0f64.to_bits());

        let mut spec = Spec::default();
        let two = constant(&mut spec, 2.0);
        let g = y_gradient(&mut spec);
        let add = node(&mut spec, [ADD, two, g, 0, 0], [0.0, 0.0]);
        assert_eq!(eval(spec, add, 0, 5, 0), 52.0);
    }

    #[test]
    fn min_and_max_follow_java_zero_signs() {
        assert_eq!(java_min(0.0, -0.0).to_bits(), (-0.0f64).to_bits());
        assert_eq!(java_max(-0.0, 0.0).to_bits(), 0.0f64.to_bits());
        assert!(java_min(f64::NAN, 1.0).is_nan());
        assert_eq!(java_max(1.0, 2.0), 2.0);
    }

    #[test]
    fn mapped_squeeze_and_half_negative() {
        assert_eq!(mapped(MappedKind::Squeeze, 2.0), 0.5 - 1.0 / 24.0);
        assert_eq!(mapped(MappedKind::HalfNegative, -3.0), -1.5);
        assert_eq!(mapped(MappedKind::QuarterNegative, -4.0), -1.0);
        assert_eq!(mapped(MappedKind::Cube, -2.0), -8.0);
    }

    #[test]
    fn range_choice_is_inclusive_below_exclusive_above() {
        let at = |y| {
            let mut spec = Spec::default();
            let g = y_gradient(&mut spec);
            let inside = constant(&mut spec, 1.0);
            let outside = constant(&mut spec, 2.0);
            let choice = node(
                &mut spec,
                [RANGE_CHOICE, g, inside, outside, 0],
                [20.0, 60.0],
            );
            eval(spec, choice, 0, y, 0)
        };
        assert_eq!(at(1), 2.0);
        assert_eq!(at(2), 1.0);
        assert_eq!(at(5), 1.0);
        assert_eq!(at(6), 2.0);
    }

    #[test]
    fn find_top_surface_scans_down_by_cell_height() {
        let mut spec = Spec::default();
        let upper = constant(&mut spec, 100.0);
        let density = node(&mut spec, [Y_GRADIENT, 0, 80, 0, 0], [1.0, -1.0]);
        let top = node(
            &mut spec,
            [FIND_TOP_SURFACE, density, upper, -64, 8],
            [0.0, 0.0],
        );
        assert_eq!(eval(spec, top, 0, 0, 0), 32.0);
    }

    fn spline_spec() -> (Spec, i32) {
        let mut spec = Spec::default();
        let coordinate = node(&mut spec, [Y_GRADIENT, 0, 1, 0, 0], [0.0, 1.0]);
        spec.spline_ints.extend([0, 0, 0, 0]);
        spec.spline_consts.push(1.0);
        spec.spline_ints.extend([0, 0, 0, 0]);
        spec.spline_consts.push(3.0);
        spec.spline_ints.extend([1, coordinate, 2, 0]);
        spec.spline_consts.push(0.0);
        spec.point_children.extend([0, 1]);
        spec.point_values.extend([0.0, 0.0, 1.0, 0.0]);
        let root = node(&mut spec, [SPLINE, 2, 0, 0, 0], [0.0, 0.0]);
        (spec, root)
    }

    #[test]
    fn spline_interpolates_with_float_math() {
        let (spec, root) = spline_spec();
        assert_eq!(eval(spec, root, 0, 0, 0), 1.0);
        let (spec, root) = spline_spec();
        assert_eq!(eval(spec, root, 0, 1, 0), 3.0);
    }

    #[test]
    fn spline_extends_flat_outside_its_range() {
        let (spec, root) = spline_spec();
        assert_eq!(eval(spec, root, 0, 2, 0), 3.0);
        let (spec, root) = spline_spec();
        assert_eq!(eval(spec, root, 0, -2, 0), 1.0);
    }

    #[test]
    fn spline_cubic_midpoint_with_derivatives() {
        let mut spec = Spec::default();
        let coordinate = node(&mut spec, [Y_GRADIENT, 0, 4, 0, 0], [0.0, 1.0]);
        spec.spline_ints.extend([0, 0, 0, 0, 0, 0, 0, 0]);
        spec.spline_consts.extend([0.0, 1.0]);
        spec.spline_ints.extend([1, coordinate, 2, 0]);
        spec.spline_consts.push(0.0);
        spec.point_children.extend([0, 1]);
        spec.point_values.extend([0.0, 2.0, 1.0, 0.0]);
        let root = node(&mut spec, [SPLINE, 2, 0, 0, 0], [0.0, 0.0]);
        assert_eq!(eval(spec, root, 0, 2, 0), 0.75);
    }

    #[test]
    fn interval_search_matches_binary_search() {
        let locations = [-1.0f32, 0.0, 0.5, 2.0];
        assert_eq!(find_interval_start(&locations, -2.0), -1);
        assert_eq!(find_interval_start(&locations, -1.0), 0);
        assert_eq!(find_interval_start(&locations, 0.25), 1);
        assert_eq!(find_interval_start(&locations, 2.0), 3);
        assert_eq!(find_interval_start(&locations, f32::NAN), 3);
    }

    #[test]
    fn rarity_mappers_step_at_thresholds() {
        assert_eq!(rarity(false, -0.6), 0.75);
        assert_eq!(rarity(false, 0.0), 1.5);
        assert_eq!(rarity(true, 0.5), 2.0);
        assert_eq!(rarity(true, 0.9), 3.0);
    }

    #[test]
    fn shifted_noise_reads_its_noise_from_the_seed() {
        let mut spec = Spec {
            seed: 7,
            ..Spec::default()
        };
        spec.noise_keys.push("minecraft:test".to_string());
        spec.noise_octaves.push(-3);
        spec.noise_amp_ends.push(2);
        spec.noise_amps.extend([1.0, 0.5]);
        let a = node(&mut spec, [SHIFT_A, 0, 0, 0, 0], [0.0, 0.0]);
        let value = eval(spec, a, 12, 0, -9);
        assert!(value.is_finite());
        assert_ne!(value, 0.0);
    }

    #[test]
    fn broken_tables_are_rejected() {
        let mut spec = Spec::default();
        node(&mut spec, [ADD, 3, 4, 0, 0], [0.0, 0.0]);
        spec.roots = vec![0];
        assert!(Tree::build(&spec).is_err());
    }
}
