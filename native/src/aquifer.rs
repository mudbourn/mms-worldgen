use crate::mth;
use crate::noisechunk::{
    Eval, ROOT_BARRIER, ROOT_DEPTH, ROOT_EROSION, ROOT_FLOODEDNESS, ROOT_LAVA, ROOT_SPREAD,
};
use crate::stage::Stage;
use crate::tree::Tree;

pub const WAY_BELOW_MIN_Y: i32 = -2032 << 4;

const SURFACE_SAMPLING_OFFSETS: [[i32; 2]; 13] = [
    [0, 0],
    [-2, -1],
    [-1, -1],
    [0, -1],
    [1, -1],
    [-3, 0],
    [-2, 0],
    [-1, 0],
    [1, 0],
    [-2, 1],
    [-1, 1],
    [0, 1],
    [1, 1],
];

// A fluid level and the palette index of its fluid.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct FluidStatus {
    pub level: i32,
    pub kind: usize,
}

impl FluidStatus {
    // The palette index at a height: the fluid below the level, air above.
    pub fn at(&self, y: i32, air: usize) -> usize {
        if y < self.level {
            self.kind
        } else {
            air
        }
    }
}

// The fluid status the generator hands out everywhere before aquifers refine it.
pub fn global_fluid(stage: &Stage, y: i32) -> FluidStatus {
    if y < (-54).min(stage.sea_level) {
        FluidStatus {
            level: -54,
            kind: stage.lava(),
        }
    } else {
        FluidStatus {
            level: stage.sea_level,
            kind: stage.default_fluid(),
        }
    }
}

// Aquifer.createDisabled: air or the global fluid wherever the density is not solid.
pub fn disabled_substance(stage: &Stage, y: i32, density: f64) -> Option<usize> {
    if density > 0.0 {
        None
    } else {
        Some(global_fluid(stage, y).at(y, stage.air()))
    }
}

pub fn similarity(i: i32, j: i32) -> f64 {
    1.0 - (j - i) as f64 / 25.0
}

pub fn flowing_update_similarity() -> f64 {
    similarity(10 * 10, 12 * 12)
}

pub fn grid_x(i: i32) -> i32 {
    i >> 4
}

pub fn from_grid_x(i: i32, j: i32) -> i32 {
    (i << 4) + j
}

pub fn grid_y(i: i32) -> i32 {
    i.div_euclid(12)
}

pub fn from_grid_y(i: i32, j: i32) -> i32 {
    i * 12 + j
}

pub fn grid_z(i: i32) -> i32 {
    i >> 4
}

pub fn from_grid_z(i: i32, j: i32) -> i32 {
    (i << 4) + j
}

// The 12 candidate cells as w, x, y offsets in the order that decides distance ties.
fn candidate_order(c2me: bool) -> [[i32; 3]; 12] {
    let mut order = [[0; 3]; 12];
    let mut at = 0;
    if c2me {
        for x in -1..=1 {
            for y in 0..=1 {
                for w in 0..=1 {
                    order[at] = [w, x, y];
                    at += 1;
                }
            }
        }
    } else {
        for w in 0..=1 {
            for x in -1..=1 {
                for y in 0..=1 {
                    order[at] = [w, x, y];
                    at += 1;
                }
            }
        }
    }
    order
}

fn adjust_surface_level(i: i32) -> i32 {
    i + 8
}

// NoiseBasedAquifer: per chunk fluid decisions with its caches.
pub struct Aquifer {
    min_grid_x: i32,
    min_grid_y: i32,
    min_grid_z: i32,
    grid_size_x: i32,
    grid_size_z: i32,
    skip_sampling_above_y: i32,
    status_cache: Vec<Option<FluidStatus>>,
    locations: Vec<Option<[i32; 3]>>,
    order: [[i32; 3]; 12],
    near_cell: [i32; 3],
    near_index: [usize; 12],
    near_location: [[i32; 3]; 12],
    schedule_fluid_update: bool,
}

impl Aquifer {
    // Sets up the grid for a chunk at block min x and z over the clamped noise range.
    pub fn new(
        stage: &Stage,
        tree: &Tree,
        eval: &mut Eval,
        chunk_min_x: i32,
        chunk_min_z: i32,
        noise_min_y: i32,
        noise_height: i32,
    ) -> Aquifer {
        let min_grid_x = grid_x(chunk_min_x - 5);
        let k = grid_x(chunk_min_x + 15 - 5) + 1;
        let grid_size_x = k - min_grid_x + 1;
        let min_grid_y = grid_y(noise_min_y + 1) - 1;
        let l = grid_y(noise_min_y + noise_height + 1) + 1;
        let m = l - min_grid_y + 1;
        let min_grid_z = grid_z(chunk_min_z - 5);
        let n = grid_z(chunk_min_z + 15 - 5) + 1;
        let grid_size_z = n - min_grid_z + 1;
        let total = (grid_size_x * m * grid_size_z) as usize;
        let max_level = eval.max_preliminary_surface_level(
            tree,
            from_grid_x(min_grid_x, 0),
            from_grid_z(min_grid_z, 0),
            from_grid_x(k, 9),
            from_grid_z(n, 9),
        );
        let p = adjust_surface_level(max_level);
        let q = grid_y(p + 12) + 1;
        Aquifer {
            min_grid_x,
            min_grid_y,
            min_grid_z,
            grid_size_x,
            grid_size_z,
            skip_sampling_above_y: from_grid_y(q, 11) - 1,
            status_cache: vec![None; total],
            locations: vec![None; total],
            order: candidate_order(stage.c2me_candidate_order),
            near_cell: [i32::MIN; 3],
            near_index: [0; 12],
            near_location: [[0; 3]; 12],
            schedule_fluid_update: false,
        }
    }

    // Whether the last substance asks for a fluid update after generation.
    pub fn should_schedule_fluid_update(&self) -> bool {
        self.schedule_fluid_update
    }

    pub fn skip_sampling_above_y(&self) -> i32 {
        self.skip_sampling_above_y
    }

    fn index(&self, i: i32, j: i32, k: i32) -> usize {
        let l = i - self.min_grid_x;
        let m = j - self.min_grid_y;
        let n = k - self.min_grid_z;
        ((m * self.grid_size_z + n) * self.grid_size_x + l) as usize
    }

    // The substance at a block: None keeps the solid block, Some is a palette index.
    #[allow(clippy::too_many_arguments)]
    pub fn compute_substance(
        &mut self,
        stage: &Stage,
        tree: &Tree,
        eval: &mut Eval,
        i: i32,
        j: i32,
        k: i32,
        d: f64,
    ) -> Option<usize> {
        self.schedule_fluid_update = false;
        if d > 0.0 {
            return None;
        }

        let air = stage.air();
        let fluid_status = global_fluid(stage, j);
        if j > self.skip_sampling_above_y {
            return Some(fluid_status.at(j, air));
        }
        if stage.is_lava(fluid_status.at(j, air)) {
            return Some(stage.lava());
        }

        let l = grid_x(i - 5);
        let m = grid_y(j + 1);
        let n = grid_z(k - 5);
        if self.near_cell != [l, m, n] {
            self.load_candidates(stage, l, m, n);
        }
        let (mut first, mut second, mut third, mut fourth) =
            (u32::MAX, u32::MAX, u32::MAX, u32::MAX);
        for (c, location) in self.near_location.iter().enumerate() {
            let af = location[0] - i;
            let ag = location[1] - j;
            let ah = location[2] - k;
            let key = (((af * af + ag * ag + ah * ah) as u32) << 4) | (15 - c as u32);
            fourth = fourth.min(third.max(key));
            third = third.min(second.max(key));
            second = second.min(first.max(key));
            first = first.min(key);
        }
        let o = (first >> 4) as i32;
        let p = (second >> 4) as i32;
        let q = (third >> 4) as i32;
        let r = (fourth >> 4) as i32;
        let s = self.near_index[15 - (first & 15) as usize];
        let t = self.near_index[15 - (second & 15) as usize];
        let u = self.near_index[15 - (third & 15) as usize];
        let v = self.near_index[15 - (fourth & 15) as usize];

        let fluid_status2 = self.status(stage, tree, eval, s);
        let e = similarity(o, p);
        let block_state = fluid_status2.at(j, air);
        if e <= 0.0 {
            if e >= flowing_update_similarity() {
                let fluid_status3 = self.status(stage, tree, eval, t);
                self.schedule_fluid_update = fluid_status2 != fluid_status3;
            }
            return Some(block_state);
        }

        if stage.is_water(block_state) && stage.is_lava(global_fluid(stage, j - 1).at(j - 1, air)) {
            self.schedule_fluid_update = true;
            return Some(block_state);
        }

        let mut barrier = f64::NAN;
        let fluid_status4 = self.status(stage, tree, eval, t);
        let f = e * pressure(
            stage,
            tree,
            eval,
            &mut barrier,
            fluid_status2,
            fluid_status4,
            [i, j, k],
        );
        if d + f > 0.0 {
            return None;
        }

        let fluid_status5 = self.status(stage, tree, eval, u);
        let g = similarity(o, q);
        if g > 0.0 {
            let h = e
                * g
                * pressure(
                    stage,
                    tree,
                    eval,
                    &mut barrier,
                    fluid_status2,
                    fluid_status5,
                    [i, j, k],
                );
            if d + h > 0.0 {
                return None;
            }
        }

        let h = similarity(p, q);
        if h > 0.0 {
            let aj = e
                * h
                * pressure(
                    stage,
                    tree,
                    eval,
                    &mut barrier,
                    fluid_status4,
                    fluid_status5,
                    [i, j, k],
                );
            if d + aj > 0.0 {
                return None;
            }
        }

        let flowing = flowing_update_similarity();
        let bl = fluid_status2 != fluid_status4;
        let bl2 = h >= flowing && fluid_status4 != fluid_status5;
        let bl3 = g >= flowing && fluid_status2 != fluid_status5;
        self.schedule_fluid_update = if !bl && !bl2 && !bl3 {
            g >= flowing
                && similarity(o, r) >= flowing
                && fluid_status2 != self.status(stage, tree, eval, v)
        } else {
            true
        };

        Some(block_state)
    }

    // Loads the twelve candidate cells around a grid cell in their tie-break order.
    fn load_candidates(&mut self, stage: &Stage, l: i32, m: i32, n: i32) {
        for c in 0..12 {
            let [w, x, y] = self.order[c];
            let z = l + w;
            let aa = m + x;
            let ab = n + y;
            let ac = self.index(z, aa, ab);
            let location = match self.locations[ac] {
                Some(location) => location,
                None => {
                    let mut random = stage.aquifer_random.at(z, aa, ab);
                    let lx = from_grid_x(z, random.next_int_bound(10));
                    let ly = from_grid_y(aa, random.next_int_bound(9));
                    let lz = from_grid_z(ab, random.next_int_bound(10));
                    self.locations[ac] = Some([lx, ly, lz]);
                    [lx, ly, lz]
                }
            };
            self.near_index[c] = ac;
            self.near_location[c] = location;
        }
        self.near_cell = [l, m, n];
    }

    fn status(&mut self, stage: &Stage, tree: &Tree, eval: &mut Eval, index: usize) -> FluidStatus {
        if let Some(status) = self.status_cache[index] {
            return status;
        }
        let [x, y, z] = self.locations[index].unwrap_or([0, 0, 0]);
        let status = compute_fluid(stage, tree, eval, x, y, z);
        self.status_cache[index] = Some(status);
        status
    }
}

#[allow(clippy::neg_cmp_op_on_partial_ord)]
fn pressure(
    stage: &Stage,
    tree: &Tree,
    eval: &mut Eval,
    barrier: &mut f64,
    a: FluidStatus,
    b: FluidStatus,
    at: [i32; 3],
) -> f64 {
    let air = stage.air();
    let i = at[1];
    let block_state = a.at(i, air);
    let block_state2 = b.at(i, air);
    if (!stage.is_lava(block_state) || !stage.is_water(block_state2))
        && (!stage.is_water(block_state) || !stage.is_lava(block_state2))
    {
        let j = (a.level - b.level).abs();
        if j == 0 {
            return 0.0;
        }

        let d = 0.5 * (a.level + b.level) as f64;
        let e = i as f64 + 0.5 - d;
        let f = j as f64 / 2.0;
        let o = f - e.abs();
        let q = if e > 0.0 {
            let p = 0.0 + o;
            if p > 0.0 {
                p / 1.5
            } else {
                p / 2.5
            }
        } else {
            let p = 3.0 + o;
            if p > 0.0 {
                p / 3.0
            } else {
                p / 10.0
            }
        };

        let r = if !(q < -2.0) && !(q > 2.0) {
            if barrier.is_nan() {
                let t = eval.chunk(tree, ROOT_BARRIER, at[0], at[1], at[2]);
                *barrier = t;
                t
            } else {
                *barrier
            }
        } else {
            0.0
        };
        2.0 * (r + q)
    } else {
        2.0
    }
}

fn compute_fluid(
    stage: &Stage,
    tree: &Tree,
    eval: &mut Eval,
    i: i32,
    j: i32,
    k: i32,
) -> FluidStatus {
    let air = stage.air();
    let fluid_status = global_fluid(stage, j);
    let mut l = i32::MAX;
    let m = j + 12;
    let n = j - 12;
    let mut bl = false;

    for offset in SURFACE_SAMPLING_OFFSETS {
        let o = i + (offset[0] << 4);
        let p = k + (offset[1] << 4);
        let q = eval.preliminary_surface_level(tree, o, p);
        let r = adjust_surface_level(q);
        let bl2 = offset[0] == 0 && offset[1] == 0;
        if bl2 && n > r {
            return fluid_status;
        }

        let bl3 = m > r;
        if bl3 || bl2 {
            let fluid_status2 = global_fluid(stage, r);
            if !stage.is_air(fluid_status2.at(r, air)) {
                if bl2 {
                    bl = true;
                }
                if bl3 {
                    return fluid_status2;
                }
            }
        }
        l = l.min(q);
    }

    let s = compute_surface_level(tree, eval, [i, j, k], fluid_status, l, bl);
    FluidStatus {
        level: s,
        kind: compute_fluid_type(stage, tree, eval, [i, j, k], fluid_status, s),
    }
}

fn compute_surface_level(
    tree: &Tree,
    eval: &mut Eval,
    at: [i32; 3],
    fluid_status: FluidStatus,
    l: i32,
    bl: bool,
) -> i32 {
    let [i, j, k] = at;
    let deep_dark = eval.point(tree, ROOT_EROSION, i, j, k) < -0.225f32 as f64
        && eval.point(tree, ROOT_DEPTH, i, j, k) > 0.9f32 as f64;
    let d;
    let e;
    if deep_dark {
        d = -1.0;
        e = -1.0;
    } else {
        let m = l + 8 - j;
        let f = if bl {
            mth::clamped_map(m as f64, 0.0, 64.0, 1.0, 0.0)
        } else {
            0.0
        };
        let g = mth::clamp(eval.point(tree, ROOT_FLOODEDNESS, i, j, k), -1.0, 1.0);
        let h = mth::map(f, 1.0, 0.0, -0.3, 0.8);
        let o = mth::map(f, 1.0, 0.0, -0.8, 0.4);
        d = g - o;
        e = g - h;
    }

    if e > 0.0 {
        fluid_status.level
    } else if d > 0.0 {
        randomized_fluid_surface_level(tree, eval, at, l)
    } else {
        WAY_BELOW_MIN_Y
    }
}

fn randomized_fluid_surface_level(tree: &Tree, eval: &mut Eval, at: [i32; 3], l: i32) -> i32 {
    let o = at[0].div_euclid(16);
    let p = at[1].div_euclid(40);
    let q = at[2].div_euclid(16);
    let r = p * 40 + 20;
    let d = eval.point(tree, ROOT_SPREAD, o, p, q) * 10.0;
    let t = mth::quantize(d, 3);
    l.min(r + t)
}

fn compute_fluid_type(
    stage: &Stage,
    tree: &Tree,
    eval: &mut Eval,
    at: [i32; 3],
    fluid_status: FluidStatus,
    l: i32,
) -> usize {
    let mut kind = fluid_status.kind;
    if l <= -10 && l != WAY_BELOW_MIN_Y && fluid_status.kind != stage.lava() {
        let o = at[0].div_euclid(64);
        let p = at[1].div_euclid(40);
        let q = at[2].div_euclid(64);
        let d = eval.point(tree, ROOT_LAVA, o, p, q);
        if d.abs() > 0.3 {
            kind = stage.lava();
        }
    }
    kind
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn grid_conversions_round_trip() {
        assert_eq!(grid_x(-1), -1);
        assert_eq!(grid_x(16), 1);
        assert_eq!(from_grid_x(-1, 9), -7);
        assert_eq!(grid_y(-1), -1);
        assert_eq!(grid_y(24), 2);
        assert_eq!(from_grid_y(-3, 11), -25);
    }

    #[test]
    fn similarity_falls_off_over_twenty_five() {
        assert_eq!(similarity(10, 10), 1.0);
        assert_eq!(similarity(0, 25), 0.0);
        assert_eq!(similarity(100, 144), -0.76);
        assert_eq!(flowing_update_similarity(), -0.76);
    }

    #[test]
    fn fluid_status_is_air_at_and_above_its_level() {
        let status = FluidStatus { level: 63, kind: 4 };
        assert_eq!(status.at(62, 0), 4);
        assert_eq!(status.at(63, 0), 0);
    }

    #[test]
    fn way_below_min_y_matches_dimension_type() {
        assert_eq!(WAY_BELOW_MIN_Y, -32512);
    }
}

#[cfg(test)]
mod order_tests {
    use super::*;

    #[test]
    fn both_candidate_orders_visit_all_twelve_cells_once() {
        for c2me in [false, true] {
            let mut seen = candidate_order(c2me).to_vec();
            seen.sort();
            seen.dedup();
            assert_eq!(seen.len(), 12);
        }
    }

    #[test]
    fn the_c2me_order_walks_w_fastest() {
        let order = candidate_order(true);
        assert_eq!(order[0], [0, -1, 0]);
        assert_eq!(order[1], [1, -1, 0]);
        assert_eq!(order[2], [0, -1, 1]);
        assert_eq!(candidate_order(false)[1], [0, -1, 1]);
    }
}
