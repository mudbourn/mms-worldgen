use crate::aquifer::{self, Aquifer};
use crate::beard::Beardifier;
use crate::noisechunk::{CellState, Eval, Layout};
use crate::random::{
    LegacyPositionalRandomFactory, LegacyRandomSource, PositionalRandomFactory, RandomSource,
    XoroshiroPositionalRandomFactory, XoroshiroRandomSource,
};
use crate::tree::Tree;
use crate::vein;
use std::sync::Arc;

pub const SETTING_INTS: usize = 18;

const FLAG_AIR: i32 = 1;
const FLAG_BLOCKS_MOTION: i32 = 2;
const FLAG_LAVA: i32 = 4;
const FLAG_WATER: i32 = 8;

// A random source of either algorithm, as the positional factories hand them out.
pub enum Rng {
    Legacy(LegacyRandomSource),
    Xoroshiro(XoroshiroRandomSource),
}

impl Rng {
    pub fn next_int_bound(&mut self, bound: i32) -> i32 {
        match self {
            Rng::Legacy(random) => random.next_int_bound(bound),
            Rng::Xoroshiro(random) => random.next_int_bound(bound),
        }
    }

    pub fn next_float(&mut self) -> f32 {
        match self {
            Rng::Legacy(random) => random.next_float(),
            Rng::Xoroshiro(random) => random.next_float(),
        }
    }
}

pub enum PosFactory {
    Legacy(LegacyPositionalRandomFactory),
    Xoroshiro(XoroshiroPositionalRandomFactory),
}

impl PosFactory {
    pub fn at(&self, x: i32, y: i32, z: i32) -> Rng {
        match self {
            PosFactory::Legacy(factory) => Rng::Legacy(factory.at(x, y, z)),
            PosFactory::Xoroshiro(factory) => Rng::Xoroshiro(factory.at(x, y, z)),
        }
    }
}

// Palette indices of the blocks the stage places by role.
#[derive(Clone, Copy, Debug)]
pub struct Roles {
    pub air: usize,
    pub default_block: usize,
    pub default_fluid: usize,
    pub lava: usize,
    pub copper_ore: usize,
    pub raw_copper: usize,
    pub granite: usize,
    pub iron_ore: usize,
    pub raw_iron: usize,
    pub tuff: usize,
}

// Everything the NOISE stage of one dimension needs apart from the chunk.
pub struct Stage {
    pub tree: Arc<Tree>,
    pub min_y: i32,
    pub height: i32,
    pub size_horizontal: i32,
    pub size_vertical: i32,
    pub sea_level: i32,
    pub aquifers: bool,
    pub ore_veins: bool,
    pub c2me_candidate_order: bool,
    pub roles: Roles,
    pub flags: Vec<i32>,
    pub aquifer_random: PosFactory,
    pub ore_random: PosFactory,
}

fn factories(seed: i64, legacy: bool) -> (PosFactory, PosFactory) {
    if legacy {
        let factory = LegacyRandomSource::new(seed).fork_positional();
        let aquifer = factory.from_hash_of("minecraft:aquifer").fork_positional();
        let ore = factory.from_hash_of("minecraft:ore").fork_positional();
        (PosFactory::Legacy(aquifer), PosFactory::Legacy(ore))
    } else {
        let factory = XoroshiroRandomSource::new(seed).fork_positional();
        let aquifer = factory.from_hash_of("minecraft:aquifer").fork_positional();
        let ore = factory.from_hash_of("minecraft:ore").fork_positional();
        (PosFactory::Xoroshiro(aquifer), PosFactory::Xoroshiro(ore))
    }
}

impl Stage {
    // Builds a stage from the settings ints and per palette entry flags sent from Java.
    pub fn new(
        tree: Arc<Tree>,
        seed: i64,
        legacy: bool,
        ints: &[i32],
        flags: Vec<i32>,
    ) -> Result<Stage, String> {
        if ints.len() != SETTING_INTS {
            return Err(format!("expected {SETTING_INTS} stage settings"));
        }
        let role = |i: usize| -> Result<usize, String> {
            let value = ints[i];
            if value >= 0 && (value as usize) < flags.len() {
                Ok(value as usize)
            } else {
                Err(format!("palette role {i} points at {value}"))
            }
        };
        let roles = Roles {
            air: role(7)?,
            default_block: role(8)?,
            default_fluid: role(9)?,
            lava: role(10)?,
            copper_ore: role(11)?,
            raw_copper: role(12)?,
            granite: role(13)?,
            iron_ore: role(14)?,
            raw_iron: role(15)?,
            tuff: role(16)?,
        };
        if tree.root_count() != 15 {
            return Err("tree does not hold the 15 router fields".to_string());
        }
        if ints[2] < 1 || ints[2] > 4 || ints[3] < 1 || ints[3] > 4 {
            return Err("noise cell sizes out of range".to_string());
        }
        let (aquifer_random, ore_random) = factories(seed, legacy);
        Ok(Stage {
            tree,
            min_y: ints[0],
            height: ints[1],
            size_horizontal: ints[2],
            size_vertical: ints[3],
            sea_level: ints[4],
            aquifers: ints[5] != 0,
            ore_veins: ints[6] != 0,
            c2me_candidate_order: ints[17] != 0,
            roles,
            flags,
            aquifer_random,
            ore_random,
        })
    }

    pub fn air(&self) -> usize {
        self.roles.air
    }

    pub fn lava(&self) -> usize {
        self.roles.lava
    }

    pub fn default_fluid(&self) -> usize {
        self.roles.default_fluid
    }

    pub fn is_air(&self, index: usize) -> bool {
        self.flags[index] & FLAG_AIR != 0
    }

    pub fn is_lava(&self, index: usize) -> bool {
        self.flags[index] & FLAG_LAVA != 0
    }

    pub fn is_water(&self, index: usize) -> bool {
        self.flags[index] & FLAG_WATER != 0
    }

    fn has_fluid(&self, index: usize) -> bool {
        self.flags[index] & (FLAG_WATER | FLAG_LAVA) != 0
    }

    fn blocks_motion(&self, index: usize) -> bool {
        self.flags[index] & FLAG_BLOCKS_MOTION != 0
    }

    // Runs the NOISE stage: two heightmaps, palette indices, then a mark count and packed mark indices.
    pub fn fill(
        &self,
        chunk_x: i32,
        chunk_z: i32,
        chunk_min_y: i32,
        chunk_max_y: i32,
        beard_ints: &[i32],
    ) -> Result<Vec<i32>, String> {
        let beard = Beardifier::from_ints(beard_ints)?;
        let total_height = chunk_max_y - chunk_min_y + 1;
        if total_height <= 0 {
            return Err("chunk height is not positive".to_string());
        }
        let min_x = chunk_x * 16;
        let min_z = chunk_z * 16;
        let noise_min_y = self.min_y.max(chunk_min_y);
        let noise_height = (self.min_y + self.height).min(chunk_max_y + 1) - noise_min_y;
        let layout = Layout::new(
            self.size_horizontal,
            self.size_vertical,
            min_x,
            min_z,
            noise_min_y,
            noise_height,
        );

        let mut result = vec![self.air() as i32; 512 + 256 * total_height as usize];
        let mut marks = Vec::new();
        let (maps, blocks) = result.split_at_mut(512);
        if layout.cell_count_y > 0 {
            self.fill_blocks(
                &layout,
                &beard,
                (min_x, min_z, noise_min_y, noise_height),
                chunk_min_y,
                blocks,
                &mut marks,
            );
        }

        maps.fill(chunk_min_y);
        let mut open = 256;
        for y in (0..total_height).rev() {
            if open == 0 {
                break;
            }
            for column in 0..256usize {
                if maps[column] != chunk_min_y {
                    continue;
                }
                let state = blocks[y as usize * 256 + column] as usize;
                if maps[256 + column] == chunk_min_y && !self.is_air(state) {
                    maps[256 + column] = chunk_min_y + y + 1;
                }
                if self.blocks_motion(state) {
                    maps[column] = chunk_min_y + y + 1;
                    open -= 1;
                }
            }
        }
        marks.sort_unstable();
        result.push(marks.len() as i32);
        result.extend(marks);
        Ok(result)
    }

    fn fill_blocks(
        &self,
        layout: &Layout,
        beard: &Beardifier,
        origin: (i32, i32, i32, i32),
        chunk_min_y: i32,
        blocks: &mut [i32],
        marks: &mut Vec<i32>,
    ) {
        let (min_x, min_z, noise_min_y, noise_height) = origin;
        let tree = &*self.tree;
        let mut eval = Eval::new(tree, *layout, beard);
        let mut aquifer = if self.aquifers {
            Some(Aquifer::new(
                self,
                tree,
                &mut eval,
                min_x,
                min_z,
                noise_min_y,
                noise_height,
            ))
        } else {
            None
        };

        let empty_above = aquifer
            .as_ref()
            .map_or(i32::MIN, |a| a.skip_sampling_above_y());
        let cw = layout.cell_width;
        let ch = layout.cell_height;
        eval.initialize_for_first_cell_x(tree);
        for q in 0..layout.cell_count_xz {
            eval.advance_cell_x(tree, q);
            for r in 0..layout.cell_count_xz {
                for t in (0..layout.cell_count_y).rev() {
                    let cell = eval.select_cell_yz(tree, t, r, empty_above);
                    if cell == CellState::Empty {
                        for u in 0..ch {
                            let v = (layout.cell_noise_min_y + t) * ch + u;
                            let state = aquifer::global_fluid(self, v).at(v, self.air());
                            if state != self.air() {
                                for y in 0..cw {
                                    for ab in 0..cw {
                                        let index = ((v - chunk_min_y) as usize) * 256
                                            + ((min_z + r * cw + ab) & 15) as usize * 16
                                            + ((min_x + q * cw + y) & 15) as usize;
                                        blocks[index] = state as i32;
                                    }
                                }
                            }
                        }
                        continue;
                    }
                    let densities = if cell == CellState::Solid {
                        Vec::new()
                    } else {
                        eval.take_density()
                    };
                    for u in (0..ch).rev() {
                        let v = (layout.cell_noise_min_y + t) * ch + u;
                        eval.update_for_y(v, u as f64 / ch as f64);
                        let surface_fluid = match &aquifer {
                            Some(a) if v > a.skip_sampling_above_y() => {
                                Some(aquifer::global_fluid(self, v).at(v, self.air()))
                            }
                            _ => None,
                        };
                        let solid_default = !(self.ore_veins && vein::may_apply(v));
                        for y in 0..cw {
                            let x = min_x + q * cw + y;
                            eval.update_for_x(x, eval.width_fraction(y));
                            for ab in 0..cw {
                                let z = min_z + r * cw + ab;
                                let lane = (((ch - 1 - u) * cw + y) * cw + ab) as usize;
                                let density = if cell == CellState::Solid {
                                    1.0
                                } else {
                                    densities[lane]
                                };
                                let mut sampled = false;
                                let state = match (density > 0.0, surface_fluid) {
                                    (true, _) if solid_default => self.roles.default_block,
                                    (false, Some(fluid)) => fluid,
                                    _ => {
                                        sampled = true;
                                        eval.update_for_z(z, eval.width_fraction(ab));
                                        self.block_state(
                                            tree,
                                            &mut eval,
                                            &mut aquifer,
                                            (x, v, z),
                                            density,
                                        )
                                    }
                                };
                                let index = ((v - chunk_min_y) as usize) * 256
                                    + ((z & 15) as usize) * 16
                                    + (x & 15) as usize;
                                blocks[index] = state as i32;
                                let scheduled = sampled
                                    && aquifer
                                        .as_ref()
                                        .is_some_and(|a| a.should_schedule_fluid_update());
                                if scheduled && self.has_fluid(state) {
                                    marks.push(index as i32);
                                }
                            }
                        }
                    }
                    if cell != CellState::Solid {
                        eval.restore_density(densities);
                    }
                }
            }
            eval.swap_slices();
        }
    }

    fn block_state(
        &self,
        tree: &Tree,
        eval: &mut Eval,
        aquifer: &mut Option<Aquifer>,
        (x, y, z): (i32, i32, i32),
        density: f64,
    ) -> usize {
        let substance = match aquifer {
            Some(aquifer) => aquifer.compute_substance(self, tree, eval, x, y, z, density),
            None => aquifer::disabled_substance(self, y, density),
        };
        if let Some(state) = substance {
            return state;
        }
        if self.ore_veins {
            if let Some(state) = vein::substance(self, tree, eval, x, y, z) {
                return state;
            }
        }
        self.roles.default_block
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tree::{self, Spec};

    fn settings(aquifers: i32) -> Vec<i32> {
        vec![
            -64, 384, 1, 2, 63, aquifers, 0, 0, 1, 2, 3, 1, 1, 1, 1, 1, 1, 0,
        ]
    }

    fn flags() -> Vec<i32> {
        vec![FLAG_AIR, FLAG_BLOCKS_MOTION, FLAG_WATER, FLAG_LAVA]
    }

    fn slope_tree() -> Arc<Tree> {
        let mut spec = Spec::default();
        let mut node = |ints: [i32; tree::NODE_INTS], doubles: [f64; tree::NODE_DOUBLES]| {
            spec.node_ints.extend(ints);
            spec.node_doubles.extend(doubles);
            (spec.node_ints.len() / tree::NODE_INTS) as i32 - 1
        };
        let zero = node([tree::CONSTANT, 0, 0, 0, 0], [0.0, 0.0]);
        let gradient = node([tree::Y_GRADIENT, -64, 320, 0, 0], [1.0, -1.0]);
        let interpolated = node([tree::MARKER, gradient, 0, 0, 0], [0.0, 0.0]);
        let mut roots = vec![zero; 15];
        roots[11] = interpolated;
        spec.roots = roots;
        Arc::new(Tree::build(&spec).unwrap())
    }

    #[test]
    fn solid_below_the_zero_crossing_and_air_above() {
        let stage = Stage::new(slope_tree(), 1, false, &settings(0), flags()).unwrap();
        let result = stage.fill(0, 0, -64, 319, &[0]).unwrap();
        let at = |x: usize, y: i32, z: usize| result[512 + (y + 64) as usize * 256 + z * 16 + x];
        assert_eq!(at(3, -64, 5), 1);
        assert_eq!(at(3, 127, 5), 1);
        assert_eq!(at(3, 128, 5), 0);
        assert_eq!(at(3, 300, 5), 0);
        assert_eq!(result[7], 128);
        assert_eq!(result[256 + 7], 128);
    }

    #[test]
    fn disabled_aquifer_floods_air_below_sea_level() {
        let mut spec = Spec::default();
        spec.node_ints.extend([tree::CONSTANT, 0, 0, 0, 0]);
        spec.node_doubles.extend([-1.0, 0.0]);
        spec.roots = vec![0; 15];
        let tree = Arc::new(Tree::build(&spec).unwrap());
        let stage = Stage::new(tree, 1, false, &settings(0), flags()).unwrap();
        let result = stage.fill(0, 0, -64, 319, &[0]).unwrap();
        let at = |y: i32| result[512 + (y + 64) as usize * 256];
        assert_eq!(at(62), 2);
        assert_eq!(at(63), 0);
        assert_eq!(at(-60), 3);
        assert_eq!(result[256], 63);
        assert_eq!(result[0], -64);
    }

    #[test]
    fn bad_settings_are_rejected() {
        assert!(Stage::new(slope_tree(), 1, false, &[0; 3], flags()).is_err());
        let mut bad = settings(0);
        bad[8] = 99;
        assert!(Stage::new(slope_tree(), 1, false, &bad, flags()).is_err());
    }
}
