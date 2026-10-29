use crate::rtree::Forest;
use crate::tree::{Hooks, MarkerKind, Memo, Tree};
use std::sync::Arc;

pub const ROOT_TEMPERATURE: usize = 4;
pub const ROOT_VEGETATION: usize = 5;
pub const ROOT_CONTINENTS: usize = 6;
pub const ROOT_EROSION: usize = 7;
pub const ROOT_DEPTH: usize = 8;
pub const ROOT_RIDGES: usize = 9;

const ROOTS: [usize; 6] = [
    ROOT_TEMPERATURE,
    ROOT_VEGETATION,
    ROOT_CONTINENTS,
    ROOT_EROSION,
    ROOT_DEPTH,
    ROOT_RIDGES,
];
const NO_SLOT: usize = usize::MAX;

// Climate.quantizeCoord.
fn quantize(value: f64) -> i64 {
    ((value as f32) * 10000.0f32) as i64
}

// Per chunk evaluation state: shared node results and the flat caches of the chunk's quart columns.
struct Eval<'a> {
    memo: Memo,
    slots: &'a [usize],
    window: Option<(i32, i32)>,
    values: Vec<[f64; 16]>,
    filled: Vec<u16>,
}

impl Hooks for Eval<'_> {
    fn memo(&mut self) -> &mut Memo {
        &mut self.memo
    }

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
        if kind != MarkerKind::FlatCache {
            return tree.eval_here(input, x, y, z, self);
        }
        let (qx, qz) = (x >> 2, z >> 2);
        let Some((base_x, base_z)) = self.window else {
            return tree.eval_here(input, x, y, z, self);
        };
        let (dx, dz) = (qx - base_x, qz - base_z);
        if self.slots[id] == NO_SLOT || !(0..4).contains(&dx) || !(0..4).contains(&dz) {
            return tree.eval_here(input, x, y, z, self);
        }
        let slot = self.slots[id];
        let index = (dx * 4 + dz) as usize;
        if self.filled[slot] & (1 << index) == 0 {
            let value = tree.compute_with(input, qx << 2, 0, qz << 2, self);
            self.values[slot][index] = value;
            self.filled[slot] |= 1 << index;
        }
        self.values[slot][index]
    }

    fn beardifier(&mut self, _x: i32, _y: i32, _z: i32) -> f64 {
        0.0
    }
}

// Climate sampler and biome search of one dimension.
pub struct Biomes {
    tree: Arc<Tree>,
    forest: Forest,
    placeholder: i32,
    slots: Vec<usize>,
    slot_count: usize,
}

impl Biomes {
    // Wraps a density tree and the exported biome search trees; placeholder is the deferred biome value or -1.
    pub fn new(tree: Arc<Tree>, forest: Forest, placeholder: i32) -> Result<Biomes, String> {
        if tree.root_count() <= ROOT_RIDGES {
            return Err("density tree has no climate fields".to_string());
        }
        if forest.tree_count() == 0 || !forest.has_tree(0) {
            return Err("biome search has no first tree".to_string());
        }
        let mut slots = vec![NO_SLOT; tree.node_count()];
        let mut slot_count = 0;
        for (id, _, kind) in tree.markers() {
            if kind == MarkerKind::FlatCache {
                slots[id] = slot_count;
                slot_count += 1;
            }
        }
        Ok(Biomes {
            tree,
            forest,
            placeholder,
            slots,
            slot_count,
        })
    }

    pub fn tree_count(&self) -> usize {
        self.forest.tree_count()
    }

    fn eval(&self, window: Option<(i32, i32)>) -> Eval<'_> {
        Eval {
            memo: Memo::new(self.tree.node_count()),
            slots: &self.slots,
            window,
            values: vec![[0.0; 16]; self.slot_count],
            filled: vec![0; self.slot_count],
        }
    }

    // Climate.Sampler.sample at a quart position, as seven values with a zero offset.
    fn target(&self, eval: &mut Eval, qx: i32, qy: i32, qz: i32) -> [i64; 7] {
        let (x, y, z) = (qx << 2, qy << 2, qz << 2);
        let mut target = [0i64; 7];
        for (slot, root) in ROOTS.iter().enumerate() {
            let id = self.tree.root_node(*root);
            target[slot] = quantize(self.tree.compute_with(id, x, y, z, eval));
        }
        target
    }

    // Samples the climate at xyz quart triples without any chunk cache; six values per point.
    pub fn climate_points(&self, quarts: &[i32]) -> Vec<i64> {
        let mut eval = self.eval(None);
        let mut out = Vec::with_capacity(quarts.len() / 3 * 6);
        for q in quarts.chunks_exact(3) {
            let target = self.target(&mut eval, q[0], q[1], q[2]);
            out.extend_from_slice(&target[..6]);
        }
        out
    }

    // Searches one tree for six value targets in order, keeping the warm start between them.
    pub fn search_batch(&self, tree: usize, targets: &[i64]) -> Option<Vec<i32>> {
        let mut last = None;
        targets
            .chunks_exact(6)
            .map(|t| {
                self.forest
                    .search(tree, &[t[0], t[1], t[2], t[3], t[4], t[5], 0], &mut last)
            })
            .collect()
    }

    // The smallest distance from a six value target to any leaf holding the value.
    pub fn best_distance(&self, tree: usize, value: i32, target: &[i64]) -> Option<i64> {
        if target.len() != 6 {
            return None;
        }
        self.forest.best_distance(
            tree,
            value,
            &[
                target[0], target[1], target[2], target[3], target[4], target[5], 0,
            ],
        )
    }

    // Fills the biome ids of one chunk in the order sections hold them; regions pick a tree per column.
    pub fn fill(
        &self,
        chunk_x: i32,
        chunk_z: i32,
        min_quart_y: i32,
        quart_count: i32,
        regions: &[i32],
    ) -> Result<Vec<i32>, String> {
        if regions.len() != 16 || quart_count <= 0 || quart_count % 4 != 0 {
            return Err("biome fill inputs are malformed".to_string());
        }
        let trees = self.forest.tree_count();
        if regions
            .iter()
            .any(|r| *r < 0 || *r as usize >= trees || !self.forest.has_tree(*r as usize))
        {
            return Err("biome region has no search tree".to_string());
        }
        let mut eval = self.eval(Some((chunk_x * 4, chunk_z * 4)));
        let mut last: Vec<Option<usize>> = vec![None; trees];
        let mut out = vec![0i32; (quart_count * 16) as usize];
        for section in 0..quart_count / 4 {
            for lx in 0..4 {
                for ly in 0..4 {
                    for lz in 0..4 {
                        let qy_index = section * 4 + ly;
                        let target = self.target(
                            &mut eval,
                            chunk_x * 4 + lx,
                            min_quart_y + qy_index,
                            chunk_z * 4 + lz,
                        );
                        let region = regions[(lx * 4 + lz) as usize] as usize;
                        let mut value = self
                            .forest
                            .search(region, &target, &mut last[region])
                            .ok_or("biome search failed")?;
                        if value == self.placeholder && self.placeholder >= 0 {
                            value = self
                                .forest
                                .search(0, &target, &mut last[0])
                                .ok_or("biome search failed")?;
                        }
                        out[((qy_index * 4 + lx) * 4 + lz) as usize] = value;
                    }
                }
            }
        }
        Ok(out)
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

    fn climate_tree() -> Arc<Tree> {
        let mut spec = Spec::default();
        let constant = node(&mut spec, [tree::CONSTANT, 0, 0, 0, 0], [0.25, 0.0]);
        let gradient = node(&mut spec, [tree::Y_GRADIENT, -64, 320, 0, 0], [-1.0, 1.0]);
        let flat = node(&mut spec, [tree::MARKER, gradient, 1, 0, 0], [0.0, 0.0]);
        spec.roots = vec![constant; 15];
        spec.roots[ROOT_DEPTH] = gradient;
        spec.roots[ROOT_RIDGES] = flat;
        Arc::new(Tree::build(&spec).unwrap())
    }

    fn forest() -> Forest {
        let mut params = vec![-100000i64, 100000].repeat(7);
        let mut meta = vec![0, 2, -1];
        for (value, depth) in [(0, -20000i64), (1, 20000)] {
            let mut spans: Vec<i64> = vec![-100000, 100000].repeat(7);
            spans[8] = depth;
            spans[9] = depth;
            params.extend(spans);
            meta.extend([0, 0, value]);
        }
        Forest::new(&params, &meta, &[1, 2], &[0]).unwrap()
    }

    #[test]
    fn quantize_truncates_the_float_product() {
        assert_eq!(quantize(0.25), 2500);
        assert_eq!(quantize(-0.99999), -9999);
        assert_eq!(quantize(f64::NAN), 0);
        assert_eq!(quantize(1.0e30), i64::MAX);
    }

    #[test]
    fn climate_points_quantize_the_router_fields() {
        let biomes = Biomes::new(climate_tree(), forest(), -1).unwrap();
        let out = biomes.climate_points(&[0, 0, 0, 3, -16, 7]);
        assert_eq!(out[..3], [2500, 2500, 2500]);
        assert_eq!(out[3], 2500);
        assert_eq!(
            out[4],
            quantize(
                biomes
                    .tree
                    .compute(biomes.tree.root_node(ROOT_DEPTH), 0, 0, 0)
            )
        );
    }

    #[test]
    fn fill_orders_cells_like_the_section_loop_and_splits_by_depth() {
        let biomes = Biomes::new(climate_tree(), forest(), -1).unwrap();
        let regions = [0i32; 16];
        let out = biomes.fill(5, -3, -16, 8, &regions).unwrap();
        assert_eq!(out.len(), 8 * 16);
        for qy in 0..8usize {
            let y = (-16 + qy as i32) * 4;
            let depth = quantize(
                biomes
                    .tree
                    .compute(biomes.tree.root_node(ROOT_DEPTH), 0, y, 0),
            );
            let expected = if (depth + 20000).abs() <= (depth - 20000).abs() {
                0
            } else {
                1
            };
            for cell in 0..16usize {
                assert_eq!(out[qy * 16 + cell], expected);
            }
        }
    }

    #[test]
    fn fill_rejects_bad_regions() {
        let biomes = Biomes::new(climate_tree(), forest(), -1).unwrap();
        assert!(biomes.fill(0, 0, 0, 4, &[1i32; 16]).is_err());
        assert!(biomes.fill(0, 0, 0, 4, &[0i32; 15]).is_err());
        assert!(biomes.fill(0, 0, 0, 3, &[0i32; 16]).is_err());
    }

    #[test]
    fn flat_cache_evaluates_at_y_zero_and_outside_the_window_directly() {
        let biomes = Biomes::new(climate_tree(), forest(), -1).unwrap();
        let mut eval = biomes.eval(Some((8, 8)));
        let cached = biomes.target(&mut eval, 9, 4, 10);
        let at_zero = biomes
            .tree
            .compute(biomes.tree.root_node(ROOT_DEPTH), 36, 0, 40);
        assert_eq!(cached[5], quantize(at_zero));
        let outside = biomes.target(&mut eval, 30, 4, 10);
        let mut plain = biomes.eval(None);
        assert_eq!(outside, biomes.target(&mut plain, 30, 4, 10));
    }
}
