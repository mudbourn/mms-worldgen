use crate::beard::Beardifier;
use crate::mth;
use crate::tree::{Hooks, MarkerKind, Tree};
use std::collections::HashMap;

pub const ROOT_BARRIER: usize = 0;
pub const ROOT_FLOODEDNESS: usize = 1;
pub const ROOT_SPREAD: usize = 2;
pub const ROOT_LAVA: usize = 3;
pub const ROOT_EROSION: usize = 7;
pub const ROOT_DEPTH: usize = 8;
pub const ROOT_PRELIMINARY_SURFACE: usize = 10;
pub const ROOT_FINAL_DENSITY: usize = 11;
pub const ROOT_VEIN_TOGGLE: usize = 12;
pub const ROOT_VEIN_RIDGED: usize = 13;
pub const ROOT_VEIN_GAP: usize = 14;

const NO_INDEX: usize = usize::MAX;

// Cell geometry of one chunk as NoiseChunk derives it.
#[derive(Clone, Copy, Debug)]
pub struct Layout {
    pub cell_width: i32,
    pub cell_height: i32,
    pub cell_count_xz: i32,
    pub cell_count_y: i32,
    pub cell_noise_min_y: i32,
    pub first_cell_x: i32,
    pub first_cell_z: i32,
    pub first_noise_x: i32,
    pub first_noise_z: i32,
    pub noise_size_xz: i32,
}

impl Layout {
    // Builds the layout for a chunk at block min x and z with clamped noise min y and height.
    pub fn new(
        size_horizontal: i32,
        size_vertical: i32,
        min_x: i32,
        min_z: i32,
        min_y: i32,
        height: i32,
    ) -> Layout {
        let cell_width = size_horizontal * 4;
        let cell_height = size_vertical * 4;
        let cell_count_xz = 16 / cell_width;
        Layout {
            cell_width,
            cell_height,
            cell_count_xz,
            cell_count_y: height.div_euclid(cell_height),
            cell_noise_min_y: min_y.div_euclid(cell_height),
            first_cell_x: min_x.div_euclid(cell_width),
            first_cell_z: min_z.div_euclid(cell_width),
            first_noise_x: min_x >> 2,
            first_noise_z: min_z >> 2,
            noise_size_xz: (cell_count_xz * cell_width) >> 2,
        }
    }

    fn slice_len(&self) -> usize {
        ((self.cell_count_xz + 1) * (self.cell_count_y + 1)) as usize
    }

    fn slice_index(&self, z: i32, y: i32) -> usize {
        (z * (self.cell_count_y + 1) + y) as usize
    }

    fn cell_len(&self) -> usize {
        (self.cell_width * self.cell_width * self.cell_height) as usize
    }
}

// One NoiseInterpolator: two corner slices and the staged interpolation state.
struct Interpolator {
    input: usize,
    slice0: Vec<f64>,
    slice1: Vec<f64>,
    noise: [f64; 8],
    value_xz: [f64; 4],
    value_z: [f64; 2],
    value: f64,
}

impl Interpolator {
    fn new(input: usize, layout: &Layout) -> Interpolator {
        Interpolator {
            input,
            slice0: vec![0.0; layout.slice_len()],
            slice1: vec![0.0; layout.slice_len()],
            noise: [0.0; 8],
            value_xz: [0.0; 4],
            value_z: [0.0; 2],
            value: 0.0,
        }
    }

    // Selects the eight corners of cell y and z; indices are n000 n001 n100 n101 n010 n011 n110 n111.
    fn select_cell_yz(&mut self, layout: &Layout, y: i32, z: i32) {
        self.noise = [
            self.slice0[layout.slice_index(z, y)],
            self.slice0[layout.slice_index(z + 1, y)],
            self.slice1[layout.slice_index(z, y)],
            self.slice1[layout.slice_index(z + 1, y)],
            self.slice0[layout.slice_index(z, y + 1)],
            self.slice0[layout.slice_index(z + 1, y + 1)],
            self.slice1[layout.slice_index(z, y + 1)],
            self.slice1[layout.slice_index(z + 1, y + 1)],
        ];
    }

    fn update_for_y(&mut self, d: f64) {
        let n = &self.noise;
        self.value_xz = [
            mth::lerp(d, n[0], n[4]),
            mth::lerp(d, n[2], n[6]),
            mth::lerp(d, n[1], n[5]),
            mth::lerp(d, n[3], n[7]),
        ];
    }

    fn update_for_x(&mut self, d: f64) {
        self.value_z = [
            mth::lerp(d, self.value_xz[0], self.value_xz[1]),
            mth::lerp(d, self.value_xz[2], self.value_xz[3]),
        ];
    }

    fn update_for_z(&mut self, d: f64) {
        self.value = mth::lerp(d, self.value_z[0], self.value_z[1]);
    }

    fn cell_value(&self, x: f64, y: f64, z: f64) -> f64 {
        let n = &self.noise;
        mth::lerp3(x, y, z, n[0], n[2], n[4], n[6], n[1], n[3], n[5], n[7])
    }

    fn swap_slices(&mut self) {
        std::mem::swap(&mut self.slice0, &mut self.slice1);
    }
}

// Cell cache filled for every block of the selected cell, from a tree node or the final density.
struct CellCache {
    input: Option<usize>,
    values: Vec<f64>,
}

// Evaluation state of one chunk that stands in for vanilla's NoiseChunk.
pub struct Eval<'a> {
    layout: Layout,
    beard: &'a Beardifier,
    in_chunk: bool,
    filling_cell: bool,
    cell_start: [i32; 3],
    in_cell: [i32; 3],
    flat: Vec<Option<Vec<f64>>>,
    interp_of: Vec<usize>,
    cell_of: Vec<usize>,
    interpolators: Vec<Interpolator>,
    cell_caches: Vec<CellCache>,
    surface_cache: HashMap<(i32, i32), i32>,
}

impl<'a> Eval<'a> {
    // Wraps the markers of a tree for one chunk; the last cell cache is the final density plus beardifier.
    pub fn new(tree: &Tree, layout: Layout, beard: &'a Beardifier) -> Eval<'a> {
        let mut interp_of = vec![NO_INDEX; tree.node_count()];
        let mut cell_of = vec![NO_INDEX; tree.node_count()];
        let mut interpolators = Vec::new();
        let mut cell_caches = Vec::new();
        for (id, input, kind) in tree.markers() {
            match kind {
                MarkerKind::Interpolated => {
                    interp_of[id] = interpolators.len();
                    interpolators.push(Interpolator::new(input, &layout));
                }
                MarkerKind::CacheAllInCell => {
                    cell_of[id] = cell_caches.len();
                    cell_caches.push(CellCache {
                        input: Some(input),
                        values: vec![0.0; layout.cell_len()],
                    });
                }
                _ => {}
            }
        }
        cell_caches.push(CellCache {
            input: None,
            values: vec![0.0; layout.cell_len()],
        });
        Eval {
            layout,
            beard,
            in_chunk: false,
            filling_cell: false,
            cell_start: [0; 3],
            in_cell: [0; 3],
            flat: vec![None; tree.node_count()],
            interp_of,
            cell_of,
            interpolators,
            cell_caches,
            surface_cache: HashMap::new(),
        }
    }

    // Evaluates a router field with the chunk itself as the function context.
    pub fn chunk(&mut self, tree: &Tree, root: usize, x: i32, y: i32, z: i32) -> f64 {
        let saved = self.in_chunk;
        self.in_chunk = true;
        let value = tree.compute_with(tree.root_node(root), x, y, z, self);
        self.in_chunk = saved;
        value
    }

    // Evaluates a router field with a single point as the function context.
    pub fn point(&mut self, tree: &Tree, root: usize, x: i32, y: i32, z: i32) -> f64 {
        let saved = self.in_chunk;
        self.in_chunk = false;
        let value = tree.compute_with(tree.root_node(root), x, y, z, self);
        self.in_chunk = saved;
        value
    }

    // NoiseChunk.preliminarySurfaceLevel: the cached surface at the quart-aligned column.
    pub fn preliminary_surface_level(&mut self, tree: &Tree, x: i32, z: i32) -> i32 {
        let qx = (x >> 2) << 2;
        let qz = (z >> 2) << 2;
        if let Some(level) = self.surface_cache.get(&(qx, qz)) {
            return *level;
        }
        let level = mth::floor(self.point(tree, ROOT_PRELIMINARY_SURFACE, qx, 0, qz));
        self.surface_cache.insert((qx, qz), level);
        level
    }

    // NoiseChunk.maxPreliminarySurfaceLevel over a block rectangle in steps of four.
    pub fn max_preliminary_surface_level(
        &mut self,
        tree: &Tree,
        min_x: i32,
        min_z: i32,
        max_x: i32,
        max_z: i32,
    ) -> i32 {
        let mut result = i32::MIN;
        let mut z = min_z;
        while z <= max_z {
            let mut x = min_x;
            while x <= max_x {
                let level = self.preliminary_surface_level(tree, x, z);
                if level > result {
                    result = level;
                }
                x += 4;
            }
            z += 4;
        }
        result
    }

    // Fills one corner slice of every interpolator at the cell column x.
    fn fill_slice(&mut self, tree: &Tree, first: bool, x_cell: i32) {
        let layout = self.layout;
        self.cell_start[0] = x_cell * layout.cell_width;
        self.in_cell[0] = 0;
        self.in_chunk = true;
        for j in 0..layout.cell_count_xz + 1 {
            self.cell_start[2] = (layout.first_cell_z + j) * layout.cell_width;
            self.in_cell[2] = 0;
            for n in 0..self.interpolators.len() {
                let input = self.interpolators[n].input;
                for i in 0..layout.cell_count_y + 1 {
                    self.cell_start[1] = (i + layout.cell_noise_min_y) * layout.cell_height;
                    self.in_cell[1] = 0;
                    let value = tree.compute_with(
                        input,
                        self.cell_start[0],
                        self.cell_start[1],
                        self.cell_start[2],
                        self,
                    );
                    let index = layout.slice_index(j, i);
                    let interpolator = &mut self.interpolators[n];
                    if first {
                        interpolator.slice0[index] = value;
                    } else {
                        interpolator.slice1[index] = value;
                    }
                }
            }
        }
        self.in_chunk = false;
    }

    pub fn initialize_for_first_cell_x(&mut self, tree: &Tree) {
        self.fill_slice(tree, true, self.layout.first_cell_x);
    }

    pub fn advance_cell_x(&mut self, tree: &Tree, i: i32) {
        self.fill_slice(tree, false, self.layout.first_cell_x + i + 1);
        self.cell_start[0] = (self.layout.first_cell_x + i) * self.layout.cell_width;
    }

    // Selects cell y and z, then fills every cell cache in lerp3 mode.
    pub fn select_cell_yz(&mut self, tree: &Tree, y: i32, z: i32) {
        let layout = self.layout;
        for interpolator in &mut self.interpolators {
            interpolator.select_cell_yz(&layout, y, z);
        }
        self.filling_cell = true;
        self.in_chunk = true;
        self.cell_start[1] = (y + layout.cell_noise_min_y) * layout.cell_height;
        self.cell_start[2] = (layout.first_cell_z + z) * layout.cell_width;
        for c in 0..self.cell_caches.len() {
            let input = self.cell_caches[c].input;
            for index in 0..layout.cell_len() as i32 {
                let cz = index % layout.cell_width;
                let k = index / layout.cell_width;
                let cx = k % layout.cell_width;
                let cy = layout.cell_height - 1 - k / layout.cell_width;
                self.in_cell = [cx, cy, cz];
                let x = self.cell_start[0] + cx;
                let yy = self.cell_start[1] + cy;
                let zz = self.cell_start[2] + cz;
                let value = match input {
                    Some(node) => tree.compute_with(node, x, yy, zz, self),
                    None => {
                        let density =
                            tree.compute_with(tree.root_node(ROOT_FINAL_DENSITY), x, yy, zz, self);
                        density + self.beard.compute(x, yy, zz)
                    }
                };
                self.cell_caches[c].values[index as usize] = value;
            }
        }
        self.filling_cell = false;
        self.in_chunk = false;
    }

    pub fn update_for_y(&mut self, y: i32, d: f64) {
        self.in_cell[1] = y - self.cell_start[1];
        for interpolator in &mut self.interpolators {
            interpolator.update_for_y(d);
        }
    }

    pub fn update_for_x(&mut self, x: i32, d: f64) {
        self.in_cell[0] = x - self.cell_start[0];
        for interpolator in &mut self.interpolators {
            interpolator.update_for_x(d);
        }
    }

    pub fn update_for_z(&mut self, z: i32, d: f64) {
        self.in_cell[2] = z - self.cell_start[2];
        for interpolator in &mut self.interpolators {
            interpolator.update_for_z(d);
        }
    }

    pub fn swap_slices(&mut self) {
        for interpolator in &mut self.interpolators {
            interpolator.swap_slices();
        }
    }

    // The final density plus beardifier cell cache at the current block.
    pub fn cached_density(&self) -> f64 {
        let layout = &self.layout;
        let index = ((layout.cell_height - 1 - self.in_cell[1]) * layout.cell_width
            + self.in_cell[0])
            * layout.cell_width
            + self.in_cell[2];
        self.cell_caches[self.cell_caches.len() - 1].values[index as usize]
    }

    fn flat_cache(&mut self, tree: &Tree, id: usize, input: usize, x: i32, y: i32, z: i32) -> f64 {
        let size = self.layout.noise_size_xz + 1;
        let qx = (x >> 2) - self.layout.first_noise_x;
        let qz = (z >> 2) - self.layout.first_noise_z;
        if qx < 0 || qz < 0 || qx >= size || qz >= size {
            return tree.compute_with(input, x, y, z, self);
        }
        if self.flat[id].is_none() {
            let saved = self.in_chunk;
            self.in_chunk = false;
            let mut values = vec![0.0; (size * size) as usize];
            for i in 0..size {
                let block_x = (self.layout.first_noise_x + i) << 2;
                for l in 0..size {
                    let block_z = (self.layout.first_noise_z + l) << 2;
                    values[(i + l * size) as usize] =
                        tree.compute_with(input, block_x, 0, block_z, self);
                }
            }
            self.in_chunk = saved;
            self.flat[id] = Some(values);
        }
        match &self.flat[id] {
            Some(values) => values[(qx + qz * size) as usize],
            None => 0.0,
        }
    }
}

impl Hooks for Eval<'_> {
    fn marker(
        &mut self,
        tree: &Tree,
        id: usize,
        input: usize,
        kind: MarkerKind,
        x: i32,
        y: i32,
        z: i32,
    ) -> f64 {
        match kind {
            MarkerKind::FlatCache => self.flat_cache(tree, id, input, x, y, z),
            MarkerKind::Interpolated if self.in_chunk && self.interp_of[id] != NO_INDEX => {
                let interpolator = &self.interpolators[self.interp_of[id]];
                if self.filling_cell {
                    interpolator.cell_value(
                        self.in_cell[0] as f64 / self.layout.cell_width as f64,
                        self.in_cell[1] as f64 / self.layout.cell_height as f64,
                        self.in_cell[2] as f64 / self.layout.cell_width as f64,
                    )
                } else {
                    interpolator.value
                }
            }
            MarkerKind::CacheAllInCell if self.in_chunk && self.cell_of[id] != NO_INDEX => {
                let [i, j, k] = self.in_cell;
                let layout = self.layout;
                if i >= 0
                    && j >= 0
                    && k >= 0
                    && i < layout.cell_width
                    && j < layout.cell_height
                    && k < layout.cell_width
                {
                    let index = ((layout.cell_height - 1 - j) * layout.cell_width + i)
                        * layout.cell_width
                        + k;
                    self.cell_caches[self.cell_of[id]].values[index as usize]
                } else {
                    tree.compute_with(input, x, y, z, self)
                }
            }
            _ => tree.compute_with(input, x, y, z, self),
        }
    }

    fn beardifier(&mut self, x: i32, y: i32, z: i32) -> f64 {
        self.beard.compute(x, y, z)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tree::{self, Spec, NODE_DOUBLES, NODE_INTS};

    fn node(spec: &mut Spec, ints: [i32; NODE_INTS], doubles: [f64; NODE_DOUBLES]) -> i32 {
        spec.node_ints.extend(ints);
        spec.node_doubles.extend(doubles);
        (spec.node_ints.len() / NODE_INTS) as i32 - 1
    }

    fn wavy_tree(kind: i32) -> Tree {
        let mut spec = Spec::default();
        let gradient = node(&mut spec, [tree::Y_GRADIENT, -64, 320, 0, 0], [1.0, -1.0]);
        let marker = node(&mut spec, [tree::MARKER, gradient, kind, 0, 0], [0.0, 0.0]);
        spec.roots = vec![marker; 15];
        Tree::build(&spec).unwrap()
    }

    fn layout() -> Layout {
        Layout::new(1, 2, 32, -16, -64, 384)
    }

    #[test]
    fn layout_follows_noise_chunk() {
        let l = layout();
        assert_eq!(l.cell_width, 4);
        assert_eq!(l.cell_height, 8);
        assert_eq!(l.cell_count_xz, 4);
        assert_eq!(l.cell_count_y, 48);
        assert_eq!(l.cell_noise_min_y, -8);
        assert_eq!(l.first_cell_x, 8);
        assert_eq!(l.first_cell_z, -4);
        assert_eq!(l.first_noise_x, 8);
        assert_eq!(l.noise_size_xz, 4);
    }

    #[test]
    fn interpolator_stages_match_manual_lerps() {
        let layout = layout();
        let mut interpolator = Interpolator::new(0, &layout);
        for (i, v) in interpolator.slice0.iter_mut().enumerate() {
            *v = (i as f64 * 0.37).sin();
        }
        for (i, v) in interpolator.slice1.iter_mut().enumerate() {
            *v = (i as f64 * 0.11).cos();
        }
        interpolator.select_cell_yz(&layout, 3, 2);
        let n = interpolator.noise;
        interpolator.update_for_y(0.375);
        interpolator.update_for_x(0.25);
        interpolator.update_for_z(0.5);
        let z0 = mth::lerp(
            0.25,
            mth::lerp(0.375, n[0], n[4]),
            mth::lerp(0.375, n[2], n[6]),
        );
        let z1 = mth::lerp(
            0.25,
            mth::lerp(0.375, n[1], n[5]),
            mth::lerp(0.375, n[3], n[7]),
        );
        assert_eq!(interpolator.value, mth::lerp(0.5, z0, z1));
        let direct = interpolator.cell_value(0.25, 0.375, 0.5);
        assert!((direct - interpolator.value).abs() < 1e-12);
    }

    #[test]
    fn corners_come_from_the_wrapped_function_at_cell_starts() {
        let beard = Beardifier::default();
        let tree = wavy_tree(0);
        let mut eval = Eval::new(&tree, layout(), &beard);
        eval.initialize_for_first_cell_x(&tree);
        let interpolator = &eval.interpolators[0];
        let l = layout();
        let y = (5 + l.cell_noise_min_y) * l.cell_height;
        let expected = tree.compute(0, 32, y, -16 + 8);
        assert_eq!(interpolator.slice0[l.slice_index(2, 5)], expected);
    }

    #[test]
    fn interpolated_density_reproduces_a_linear_function() {
        let beard = Beardifier::default();
        let tree = wavy_tree(0);
        let l = layout();
        let mut eval = Eval::new(&tree, l, &beard);
        eval.initialize_for_first_cell_x(&tree);
        eval.advance_cell_x(&tree, 0);
        eval.select_cell_yz(&tree, 40, 1);
        let y = (40 + l.cell_noise_min_y) * l.cell_height + 5;
        eval.update_for_y(y, 5.0 / 8.0);
        eval.update_for_x(33, 0.25);
        eval.update_for_z(-16 + 4 + 2, 0.5);
        let expected = tree.compute(0, 33, y, -16 + 4 + 2);
        assert!((eval.cached_density() - expected).abs() < 1e-12);
    }

    #[test]
    fn flat_cache_reads_quart_columns_at_y_zero() {
        let beard = Beardifier::default();
        let mut spec = Spec::default();
        let gradient = node(&mut spec, [tree::Y_GRADIENT, 0, 100, 0, 0], [0.0, 100.0]);
        let marker = node(&mut spec, [tree::MARKER, gradient, 1, 0, 0], [0.0, 0.0]);
        spec.roots = vec![marker; 15];
        let tree = Tree::build(&spec).unwrap();
        let mut eval = Eval::new(&tree, layout(), &beard);
        assert_eq!(eval.point(&tree, 0, 33, 50, -15), 0.0);
        assert_eq!(eval.point(&tree, 0, 500, 50, 500), 50.0);
    }

    #[test]
    fn interpolated_marker_outside_the_chunk_context_evaluates_directly() {
        let beard = Beardifier::default();
        let tree = wavy_tree(0);
        let mut eval = Eval::new(&tree, layout(), &beard);
        let value = eval.point(&tree, 0, 1, 100, 1);
        assert_eq!(value, tree.compute(0, 1, 100, 1));
    }
}
