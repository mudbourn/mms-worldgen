use crate::mth;
use crate::noise::NormalNoise;
use crate::random::{
    LegacyPositionalRandomFactory, LegacyRandomSource, PositionalRandomFactory, RandomSource,
    XoroshiroPositionalRandomFactory, XoroshiroRandomSource,
};
use crate::simplex::PerlinSimplexNoise;
use crate::stage::{PosFactory, Rng};
use crate::tree::Tree;
use std::sync::Arc;

pub const RULE_INTS: usize = 5;
pub const COND_INTS: usize = 5;
pub const BIOME_INTS: usize = 3;

// Palette entries every surface palette starts with.
pub const STATE_WHITE_TERRACOTTA: i32 = 0;
pub const STATE_ORANGE_TERRACOTTA: i32 = 1;
pub const STATE_TERRACOTTA: i32 = 2;
pub const STATE_YELLOW_TERRACOTTA: i32 = 3;
pub const STATE_BROWN_TERRACOTTA: i32 = 4;
pub const STATE_RED_TERRACOTTA: i32 = 5;
pub const STATE_LIGHT_GRAY_TERRACOTTA: i32 = 6;
pub const STATE_PACKED_ICE: i32 = 7;
pub const STATE_SNOW_BLOCK: i32 = 8;
pub const FIXED_STATES: usize = 9;

// Noise slots every surface spec starts with, rule noises follow.
const NOISE_SURFACE: usize = 0;
const NOISE_SURFACE_SECONDARY: usize = 1;
const NOISE_CLAY_BANDS_OFFSET: usize = 2;
const NOISE_BADLANDS_PILLAR: usize = 3;
const NOISE_BADLANDS_PILLAR_ROOF: usize = 4;
const NOISE_BADLANDS_SURFACE: usize = 5;
const NOISE_ICEBERG_PILLAR: usize = 6;
const NOISE_ICEBERG_PILLAR_ROOF: usize = 7;
const NOISE_ICEBERG_SURFACE: usize = 8;
pub const FIXED_NOISES: usize = 9;

pub const R_BLOCK: i32 = 0;
pub const R_SEQUENCE: i32 = 1;
pub const R_TEST: i32 = 2;
pub const R_BANDLANDS: i32 = 3;
pub const R_BANDS: i32 = 4;
pub const R_GRID: i32 = 5;
pub const R_BY_BIOME: i32 = 6;

pub const C_BIOME: i32 = 0;
pub const C_NOISE: i32 = 1;
pub const C_GRADIENT: i32 = 2;
pub const C_Y: i32 = 3;
pub const C_WATER: i32 = 4;
pub const C_TEMPERATURE: i32 = 5;
pub const C_STEEP: i32 = 6;
pub const C_HOLE: i32 = 7;
pub const C_ABOVE_PRELIMINARY: i32 = 8;
pub const C_STONE_DEPTH: i32 = 9;
pub const C_NOT: i32 = 10;
pub const C_ALL: i32 = 11;
pub const C_ANY: i32 = 12;
pub const C_SLOPE: i32 = 13;

const BIOME_FLAG_BADLANDS_EXTENSION: i32 = 1;
const BIOME_FLAG_ICEBERG_EXTENSION: i32 = 2;

pub const FLAG_AIR: i32 = 1;
pub const FLAG_FLUID: i32 = 2;
pub const FLAG_BLOCKS_MOTION: i32 = 4;
pub const FLAG_DEFAULT_BLOCK: i32 = 8;
pub const FLAG_WATER: i32 = 16;

const WAY_BELOW_MIN_Y: i32 = -32512;
const BAND_COUNT: usize = 192;
const PRELIMINARY_ROOT: usize = 10;
const GRID_QUARTS: i32 = 6;

// The root positional random factory of a world, which the surface system draws from.
enum Root {
    Legacy(LegacyPositionalRandomFactory),
    Xoroshiro(XoroshiroPositionalRandomFactory),
}

impl Root {
    fn new(seed: i64, legacy: bool) -> Root {
        if legacy {
            Root::Legacy(LegacyRandomSource::new(seed).fork_positional())
        } else {
            Root::Xoroshiro(XoroshiroRandomSource::new(seed).fork_positional())
        }
    }

    fn at(&self, x: i32, y: i32, z: i32) -> Rng {
        match self {
            Root::Legacy(factory) => Rng::Legacy(factory.at(x, y, z)),
            Root::Xoroshiro(factory) => Rng::Xoroshiro(factory.at(x, y, z)),
        }
    }

    fn noise(&self, key: &str, first_octave: i32, amplitudes: &[f64]) -> NormalNoise {
        match self {
            Root::Legacy(factory) => {
                NormalNoise::create(&mut factory.from_hash_of(key), first_octave, amplitudes)
            }
            Root::Xoroshiro(factory) => {
                NormalNoise::create(&mut factory.from_hash_of(key), first_octave, amplitudes)
            }
        }
    }

    fn named_factory(&self, name: &str) -> PosFactory {
        match self {
            Root::Legacy(factory) => {
                PosFactory::Legacy(factory.from_hash_of(name).fork_positional())
            }
            Root::Xoroshiro(factory) => {
                PosFactory::Xoroshiro(factory.from_hash_of(name).fork_positional())
            }
        }
    }

    fn clay_bands(&self) -> [i32; BAND_COUNT] {
        match self {
            Root::Legacy(factory) => {
                generate_bands(&mut factory.from_hash_of("minecraft:clay_bands"))
            }
            Root::Xoroshiro(factory) => {
                generate_bands(&mut factory.from_hash_of("minecraft:clay_bands"))
            }
        }
    }
}

fn make_bands<R: RandomSource>(random: &mut R, bands: &mut [i32], thickness: i32, state: i32) {
    let count = random.next_int_bound(10) + 6;
    for _ in 0..count {
        let length = thickness + random.next_int_bound(3);
        let start = random.next_int_bound(bands.len() as i32) as usize;
        let mut n = 0;
        while start + n < bands.len() && (n as i32) < length {
            bands[start + n] = state;
            n += 1;
        }
    }
}

fn generate_bands<R: RandomSource>(random: &mut R) -> [i32; BAND_COUNT] {
    let mut bands = [STATE_TERRACOTTA; BAND_COUNT];
    let mut i = 0usize;
    while i < BAND_COUNT {
        i += random.next_int_bound(5) as usize + 1;
        if i < BAND_COUNT {
            bands[i] = STATE_ORANGE_TERRACOTTA;
        }
        i += 1;
    }
    make_bands(random, &mut bands, 1, STATE_YELLOW_TERRACOTTA);
    make_bands(random, &mut bands, 2, STATE_BROWN_TERRACOTTA);
    make_bands(random, &mut bands, 1, STATE_RED_TERRACOTTA);
    let target = random.next_int_bound(7) + 9;
    let mut placed = 0;
    let mut k = 0usize;
    while placed < target && k < BAND_COUNT {
        bands[k] = STATE_WHITE_TERRACOTTA;
        if k > 1 && random.next_boolean() {
            bands[k - 1] = STATE_LIGHT_GRAY_TERRACOTTA;
        }
        if k + 1 < BAND_COUNT && random.next_boolean() {
            bands[k + 1] = STATE_LIGHT_GRAY_TERRACOTTA;
        }
        placed += 1;
        k += random.next_int_bound(16) as usize + 4;
    }
    bands
}

// Per biome climate data the temperature condition and the extensions need.
#[derive(Clone, Copy)]
struct BiomeInfo {
    base_temperature: f32,
    frozen: bool,
    flags: i32,
}

// Everything a seeded surface system needs, as Java hands it over.
#[derive(Default)]
pub struct SurfaceSpec {
    pub seed: i64,
    pub legacy: bool,
    pub sea_level: i32,
    pub default_block: i32,
    pub rule_ints: Vec<i32>,
    pub cond_ints: Vec<i32>,
    pub cond_doubles: Vec<f64>,
    pub lists: Vec<i32>,
    pub root: i32,
    pub noise_keys: Vec<String>,
    pub noise_octaves: Vec<i32>,
    pub noise_amp_ends: Vec<i32>,
    pub noise_amps: Vec<f64>,
    pub gradient_names: Vec<String>,
    pub biome_ints: Vec<i32>,
}

pub struct Surface {
    tree: Arc<Tree>,
    legacy: bool,
    sea_level: i32,
    default_block: i32,
    rules: Vec<[i32; RULE_INTS]>,
    conds: Vec<[i32; COND_INTS]>,
    cond_doubles: Vec<[f64; 2]>,
    lists: Vec<i32>,
    root: usize,
    noises: Vec<NormalNoise>,
    gradients: Vec<PosFactory>,
    noise_random: Root,
    clay_bands: [i32; BAND_COUNT],
    biomes: Vec<BiomeInfo>,
    temperature_noise: PerlinSimplexNoise,
    frozen_noise: PerlinSimplexNoise,
    info_noise: PerlinSimplexNoise,
    seq_filter: Vec<u32>,
    seq_ranges: Vec<(u32, u32)>,
    seq_children: Vec<i32>,
}

// Biomes a rule or condition can match, None for every biome.
type BiomeSet = Option<Vec<u64>>;

fn set_has(set: &BiomeSet, biome: usize) -> bool {
    match set {
        None => true,
        Some(words) => words[biome >> 6] >> (biome & 63) & 1 != 0,
    }
}

fn set_union(a: &BiomeSet, b: &BiomeSet) -> BiomeSet {
    match (a, b) {
        (Some(x), Some(y)) => Some(x.iter().zip(y).map(|(p, q)| p | q).collect()),
        _ => None,
    }
}

fn set_intersect(a: &BiomeSet, b: &BiomeSet) -> BiomeSet {
    match (a, b) {
        (None, other) | (other, None) => other.clone(),
        (Some(x), Some(y)) => Some(x.iter().zip(y).map(|(p, q)| p & q).collect()),
    }
}

// Static biome reach of the rule tree, memoized per node.
struct Reach<'a> {
    s: &'a Surface,
    words: usize,
    rules: Vec<Option<BiomeSet>>,
    conds: Vec<Option<BiomeSet>>,
}

impl Reach<'_> {
    fn list_set(&self, start: i32, invert: bool) -> BiomeSet {
        let mut words = vec![0u64; self.words];
        for biome in 0..self.s.biomes.len() {
            if (self.s.lists[start as usize + biome] != 0) != invert {
                words[biome >> 6] |= 1 << (biome & 63);
            }
        }
        Some(words)
    }

    fn cond(&mut self, id: i32) -> BiomeSet {
        if let Some(set) = &self.conds[id as usize] {
            return set.clone();
        }
        let s = self.s;
        let node = s.conds[id as usize];
        let set = match node[0] {
            C_BIOME => self.list_set(node[1], false),
            C_NOT => {
                let inner = s.conds[node[1] as usize];
                if inner[0] == C_BIOME {
                    self.list_set(inner[1], true)
                } else {
                    None
                }
            }
            C_ALL => {
                let mut set = None;
                for i in 0..node[2] {
                    let child = self.cond(s.lists[(node[1] + i) as usize]);
                    set = set_intersect(&set, &child);
                }
                set
            }
            C_ANY => {
                let mut set = Some(vec![0u64; self.words]);
                for i in 0..node[2] {
                    let child = self.cond(s.lists[(node[1] + i) as usize]);
                    set = set_union(&set, &child);
                }
                set
            }
            _ => None,
        };
        self.conds[id as usize] = Some(set.clone());
        set
    }

    fn rule(&mut self, id: i32) -> BiomeSet {
        if let Some(set) = &self.rules[id as usize] {
            return set.clone();
        }
        let s = self.s;
        let node = s.rules[id as usize];
        let set = match node[0] {
            R_TEST => {
                let cond = self.cond(node[1]);
                let then = self.rule(node[2]);
                set_intersect(&cond, &then)
            }
            R_SEQUENCE => {
                let mut set = Some(vec![0u64; self.words]);
                for i in 0..node[2] {
                    let child = self.rule(s.lists[(node[1] + i) as usize]);
                    set = set_union(&set, &child);
                }
                set
            }
            R_GRID => {
                let mut set = Some(vec![0u64; self.words]);
                for i in 0..256 {
                    let child = self.rule(s.lists[(node[1] + i) as usize]);
                    set = set_union(&set, &child);
                }
                set
            }
            R_BY_BIOME => {
                let mut set = self.rule(node[1]);
                if let Some(words) = &mut set {
                    for biome in 0..s.biomes.len() {
                        let target = s.lists[node[2] as usize + biome];
                        if target >= 0 && set_has(&self.rule(target), biome) {
                            words[biome >> 6] |= 1 << (biome & 63);
                        }
                    }
                }
                set
            }
            _ => None,
        };
        self.rules[id as usize] = Some(set.clone());
        set
    }
}

// The chunk and its neighbourhood as one surface build sees it.
pub struct Input<'a> {
    pub chunk_x: i32,
    pub chunk_z: i32,
    pub min_y: i32,
    pub max_y: i32,
    pub biome_seed: i64,
    pub maps: &'a [i32],
    pub blocks: &'a [i32],
    pub flags: &'a [i32],
    pub biomes: &'a [i32],
}

fn in_range(value: i32, limit: usize, what: &str) -> Result<usize, String> {
    if value >= 0 && (value as usize) < limit {
        Ok(value as usize)
    } else {
        Err(format!("{what} {value} out of range {limit}"))
    }
}

impl Surface {
    // Builds the seeded surface system from its flat description.
    pub fn new(tree: Arc<Tree>, spec: &SurfaceSpec) -> Result<Surface, String> {
        if tree.root_count() != 15 {
            return Err("tree does not hold the 15 router fields".to_string());
        }
        if !spec.rule_ints.len().is_multiple_of(RULE_INTS)
            || !spec.cond_ints.len().is_multiple_of(COND_INTS)
            || spec.cond_doubles.len() != spec.cond_ints.len() / COND_INTS * 2
            || !spec.biome_ints.len().is_multiple_of(BIOME_INTS)
        {
            return Err("surface arrays have bad lengths".to_string());
        }
        let noise_count = spec.noise_keys.len();
        if noise_count < FIXED_NOISES
            || spec.noise_octaves.len() != noise_count
            || spec.noise_amp_ends.len() != noise_count
        {
            return Err("surface noise tables are malformed".to_string());
        }
        let root = Root::new(spec.seed, spec.legacy);
        let mut noises = Vec::with_capacity(noise_count);
        let mut start = 0usize;
        for (i, key) in spec.noise_keys.iter().enumerate() {
            let end = in_range(
                spec.noise_amp_ends[i],
                spec.noise_amps.len() + 1,
                "amplitude end",
            )?;
            if end < start {
                return Err("amplitude ends are not increasing".to_string());
            }
            noises.push(root.noise(key, spec.noise_octaves[i], &spec.noise_amps[start..end]));
            start = end;
        }
        let gradients = spec
            .gradient_names
            .iter()
            .map(|name| root.named_factory(name))
            .collect();
        let rules: Vec<[i32; RULE_INTS]> = spec
            .rule_ints
            .chunks_exact(RULE_INTS)
            .map(|c| c.try_into().unwrap())
            .collect();
        let conds: Vec<[i32; COND_INTS]> = spec
            .cond_ints
            .chunks_exact(COND_INTS)
            .map(|c| c.try_into().unwrap())
            .collect();
        let cond_doubles = spec
            .cond_doubles
            .chunks_exact(2)
            .map(|c| [c[0], c[1]])
            .collect();
        let biomes: Vec<BiomeInfo> = spec
            .biome_ints
            .chunks_exact(BIOME_INTS)
            .map(|c| BiomeInfo {
                base_temperature: f32::from_bits(c[0] as u32),
                frozen: c[1] != 0,
                flags: c[2],
            })
            .collect();
        let surface = Surface {
            tree,
            legacy: spec.legacy,
            sea_level: spec.sea_level,
            default_block: spec.default_block,
            rules,
            conds,
            cond_doubles,
            lists: spec.lists.clone(),
            root: in_range(spec.root, spec.rule_ints.len() / RULE_INTS, "root rule")?,
            noises,
            gradients,
            clay_bands: root.clay_bands(),
            noise_random: root,
            biomes,
            temperature_noise: PerlinSimplexNoise::new(1234, 1),
            frozen_noise: PerlinSimplexNoise::new(3456, 3),
            info_noise: PerlinSimplexNoise::new(2345, 1),
            seq_filter: Vec::new(),
            seq_ranges: Vec::new(),
            seq_children: Vec::new(),
        };
        surface.validate()?;
        let mut surface = surface;
        surface.filter_sequences();
        Ok(surface)
    }

    // Checks every index the rule and condition nodes hold.
    fn validate(&self) -> Result<(), String> {
        let list = |start: i32, count: i32| -> Result<(), String> {
            if start < 0 || count < 0 || (start as usize) + (count as usize) > self.lists.len() {
                return Err(format!("list {start}+{count} out of range"));
            }
            Ok(())
        };
        let biome_count = self.biomes.len() as i32;
        for rule in &self.rules {
            match rule[0] {
                R_BLOCK | R_BANDLANDS => {}
                R_SEQUENCE => {
                    list(rule[1], rule[2])?;
                    for i in 0..rule[2] {
                        in_range(self.lists[(rule[1] + i) as usize], self.rules.len(), "rule")?;
                    }
                }
                R_TEST => {
                    in_range(rule[1], self.conds.len(), "condition")?;
                    in_range(rule[2], self.rules.len(), "rule")?;
                }
                R_BANDS => {
                    list(rule[1], rule[2])?;
                    if rule[2] < 2 {
                        return Err("bands rule needs two states".to_string());
                    }
                }
                R_GRID => {
                    list(rule[1], 256)?;
                    for i in 0..256 {
                        in_range(self.lists[(rule[1] + i) as usize], self.rules.len(), "rule")?;
                    }
                }
                R_BY_BIOME => {
                    in_range(rule[1], self.rules.len(), "rule")?;
                    list(rule[2], biome_count)?;
                    for i in 0..biome_count {
                        let target = self.lists[(rule[2] + i) as usize];
                        if target >= 0 {
                            in_range(target, self.rules.len(), "rule")?;
                        }
                    }
                }
                kind => return Err(format!("unknown rule kind {kind}")),
            }
        }
        for cond in &self.conds {
            match cond[0] {
                C_BIOME => list(cond[1], biome_count)?,
                C_NOISE => {
                    in_range(cond[1], self.noises.len(), "noise")?;
                }
                C_GRADIENT => {
                    in_range(cond[1], self.gradients.len(), "gradient")?;
                }
                C_Y | C_WATER | C_TEMPERATURE | C_STEEP | C_HOLE | C_ABOVE_PRELIMINARY
                | C_STONE_DEPTH | C_SLOPE => {}
                C_NOT => {
                    in_range(cond[1], self.conds.len(), "condition")?;
                }
                C_ALL | C_ANY => {
                    list(cond[1], cond[2])?;
                    for i in 0..cond[2] {
                        in_range(
                            self.lists[(cond[1] + i) as usize],
                            self.conds.len(),
                            "condition",
                        )?;
                    }
                }
                kind => return Err(format!("unknown condition kind {kind}")),
            }
        }
        Ok(())
    }

    // Per biome child lists for sequences where some children cannot match a biome.
    fn filter_sequences(&mut self) {
        let biome_count = self.biomes.len();
        let mut reach = Reach {
            s: self,
            words: biome_count.div_ceil(64),
            rules: vec![None; self.rules.len()],
            conds: vec![None; self.conds.len()],
        };
        let mut filter = vec![u32::MAX; self.rules.len()];
        let mut ranges = Vec::new();
        let mut children = Vec::new();
        for (id, node) in self.rules.iter().enumerate() {
            if node[0] != R_SEQUENCE || biome_count == 0 {
                continue;
            }
            let list: Vec<i32> = (0..node[2])
                .map(|i| self.lists[(node[1] + i) as usize])
                .collect();
            let sets: Vec<BiomeSet> = list.iter().map(|child| reach.rule(*child)).collect();
            if sets.iter().all(|set| set.is_none()) {
                continue;
            }
            filter[id] = ranges.len() as u32;
            for biome in 0..biome_count {
                let start = children.len() as u32;
                for (child, set) in list.iter().zip(&sets) {
                    if set_has(set, biome) {
                        children.push(*child);
                    }
                }
                ranges.push((start, children.len() as u32 - start));
            }
        }
        self.seq_filter = filter;
        self.seq_ranges = ranges;
        self.seq_children = children;
    }

    pub fn biome_count(&self) -> usize {
        self.biomes.len()
    }

    // Height adjusted temperature of a biome at a block, as Biome.getTemperature computes it.
    pub fn temperature(&self, biome: usize, x: i32, y: i32, z: i32) -> f32 {
        let info = self.biomes[biome];
        let mut temperature = info.base_temperature;
        if info.frozen {
            let d = self.frozen_noise.value(x as f64 * 0.05, z as f64 * 0.05) * 7.0;
            let e = self.info_noise.value(x as f64 * 0.2, z as f64 * 0.2);
            if d + e < 0.3 {
                let h = self.info_noise.value(x as f64 * 0.09, z as f64 * 0.09);
                if h < 0.8 {
                    temperature = 0.2f32;
                }
            }
        }
        let limit = self.sea_level + 17;
        if y > limit {
            let g = (self
                .temperature_noise
                .value((x as f32 / 8.0f32) as f64, (z as f32 / 8.0f32) as f64)
                * 8.0) as f32;
            temperature - (g + y as f32 - limit as f32) * 0.05f32 / 40.0f32
        } else {
            temperature
        }
    }

    #[allow(clippy::neg_cmp_op_on_partial_ord)]
    pub fn cold_enough_to_snow(&self, biome: usize, x: i32, y: i32, z: i32) -> bool {
        !(self.temperature(biome, x, y, z) >= 0.15f32)
    }

    // Runs the surface step for one chunk and returns heightmaps, block changes and marks.
    pub fn build(&self, input: &Input) -> Result<Vec<i32>, String> {
        let total_height = input.max_y - input.min_y + 1;
        if total_height <= 0 || total_height % 4 != 0 {
            return Err("chunk height is not a positive multiple of four".to_string());
        }
        let grid = (GRID_QUARTS * GRID_QUARTS * (total_height / 4)) as usize;
        if input.maps.len() != 512
            || input.blocks.len() != 256 * total_height as usize
            || input.biomes.len() != grid
        {
            return Err("surface input arrays have bad lengths".to_string());
        }
        for state in input.blocks {
            in_range(*state, input.flags.len(), "block state")?;
        }
        for biome in input.biomes {
            in_range(*biome, self.biomes.len(), "biome")?;
        }
        in_range(self.default_block, input.flags.len(), "default block")?;
        for rule in &self.rules {
            if rule[0] == R_BLOCK {
                in_range(rule[1], input.flags.len(), "rule state")?;
            }
        }
        for state in self.clay_bands {
            in_range(state, input.flags.len(), "band state")?;
        }
        for i in 0..self.rules.len() {
            if self.rules[i][0] == R_BANDS {
                for j in 0..self.rules[i][2] {
                    in_range(
                        self.lists[(self.rules[i][1] + j) as usize],
                        input.flags.len(),
                        "band state",
                    )?;
                }
            }
        }
        let mut run = Run::new(self, input);
        run.build_columns();
        Ok(run.finish())
    }
}

// Quart rows a column's biome zoom can touch, with one spare row each side.
fn corner_slots(inp: &Input) -> usize {
    ((inp.max_y - inp.min_y + 1) / 4 + 4) as usize
}

fn quart_index(value: i32) -> i32 {
    value >> 2
}

fn fiddle(l: i64) -> f64 {
    let d = (l >> 24).rem_euclid(1024) as f64 / 1024.0;
    (d - 0.5) * 0.9
}

fn lcg(l: i64, m: i64) -> i64 {
    l.wrapping_mul(
        l.wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407),
    )
    .wrapping_add(m)
}

fn fiddled_distance(seed: i64, i: i32, j: i32, k: i32, d: f64, e: f64, f: f64) -> f64 {
    let (g, h, n) = fiddles(seed, i, j, k);
    let a = f + n;
    let b = e + h;
    let c = d + g;
    a * a + b * b + c * c
}

// The seeded x, y and z jitter of one quart corner.
fn fiddles(seed: i64, i: i32, j: i32, k: i32) -> (f64, f64, f64) {
    let mut m = seed;
    m = lcg(m, i as i64);
    m = lcg(m, j as i64);
    m = lcg(m, k as i64);
    m = lcg(m, i as i64);
    m = lcg(m, j as i64);
    m = lcg(m, k as i64);
    let g = fiddle(m);
    m = lcg(m, seed);
    let h = fiddle(m);
    m = lcg(m, seed);
    let n = fiddle(m);
    (g, h, n)
}

// Biome lookup over the quart grid of a chunk, with BiomeManager's seeded fuzzy zoom.
#[allow(clippy::too_many_arguments)]
pub fn zoom_biome(
    grid: &[i32],
    seed: i64,
    chunk_x: i32,
    chunk_z: i32,
    min_y: i32,
    max_y: i32,
    x: i32,
    y: i32,
    z: i32,
) -> usize {
    let i = x - 2;
    let j = y - 2;
    let k = z - 2;
    let l = i >> 2;
    let m = j >> 2;
    let n = k >> 2;
    let d = (i & 3) as f64 / 4.0;
    let e = (j & 3) as f64 / 4.0;
    let f = (k & 3) as f64 / 4.0;
    let mut best = 0;
    let mut distance = f64::INFINITY;
    for p in 0..8 {
        let bx = p & 4 == 0;
        let by = p & 2 == 0;
        let bz = p & 1 == 0;
        let q = if bx { l } else { l + 1 };
        let r = if by { m } else { m + 1 };
        let s = if bz { n } else { n + 1 };
        let h = if bx { d } else { d - 1.0 };
        let t = if by { e } else { e - 1.0 };
        let u = if bz { f } else { f - 1.0 };
        let v = fiddled_distance(seed, q, r, s, h, t, u);
        if distance > v {
            best = p;
            distance = v;
        }
    }
    let qx = if best & 4 == 0 { l } else { l + 1 };
    let qy = if best & 2 == 0 { m } else { m + 1 };
    let qz = if best & 1 == 0 { n } else { n + 1 };
    let min_quart = quart_index(min_y);
    let max_quart = quart_index(min_y) + quart_index(max_y - min_y + 1) - 1;
    let qy = qy.clamp(min_quart, max_quart);
    let gx = qx - (chunk_x * 4 - 1);
    let gz = qz - (chunk_z * 4 - 1);
    let index = (((qy - min_quart) * GRID_QUARTS + gz) * GRID_QUARTS + gx) as usize;
    grid[index] as usize
}

// Mutable state of one surface build: the blocks, heightmaps and the rule context.
struct Run<'a> {
    s: &'a Surface,
    inp: &'a Input<'a>,
    blocks: Vec<i32>,
    floor: Vec<i32>,
    surface: Vec<i32>,
    marks: Vec<i32>,
    column_x: i32,
    column_z: i32,
    block_x: i32,
    block_z: i32,
    surface_depth: i32,
    secondary: Option<f64>,
    min_surface: Option<i32>,
    cond_stamp: Vec<u32>,
    cond_value: Vec<bool>,
    column_stamp: u32,
    block_cond_stamp: Vec<u32>,
    block_cond_value: Vec<bool>,
    block_stamp: u32,
    block_y: i32,
    water_height: i32,
    stone_below: i32,
    stone_above: i32,
    corner_stamp: Vec<u32>,
    corners: Vec<[(f64, f64, f64); 4]>,
    biome: Option<usize>,
    cell: Option<(i32, i32, [i32; 4])>,
}

impl<'a> Run<'a> {
    fn new(s: &'a Surface, inp: &'a Input<'a>) -> Run<'a> {
        Run {
            s,
            inp,
            blocks: inp.blocks.to_vec(),
            floor: inp.maps[..256].to_vec(),
            surface: inp.maps[256..].to_vec(),
            marks: Vec::new(),
            column_x: 0,
            column_z: 0,
            block_x: 0,
            block_z: 0,
            surface_depth: 0,
            secondary: None,
            min_surface: None,
            cond_stamp: vec![0; s.conds.len()],
            cond_value: vec![false; s.conds.len()],
            column_stamp: 0,
            block_cond_stamp: vec![0; s.conds.len()],
            block_cond_value: vec![false; s.conds.len()],
            block_stamp: 0,
            block_y: 0,
            water_height: i32::MIN,
            stone_below: 0,
            stone_above: 0,
            corner_stamp: vec![0; corner_slots(inp)],
            corners: vec![[(0.0, 0.0, 0.0); 4]; corner_slots(inp)],
            biome: None,
            cell: None,
        }
    }

    // Heightmaps, then changed blocks and fluid marks.
    fn finish(self) -> Vec<i32> {
        let mut result = Vec::with_capacity(512 + 1 + self.marks.len() + 1);
        result.extend_from_slice(&self.floor);
        result.extend_from_slice(&self.surface);
        let mut changed = Vec::new();
        for (index, state) in self.blocks.iter().enumerate() {
            if *state != self.inp.blocks[index] {
                changed.push(index as i32);
                changed.push(*state);
            }
        }
        result.push(changed.len() as i32 / 2);
        result.extend(changed);
        result.push(self.marks.len() as i32);
        result.extend(self.marks);
        result
    }

    fn flags_of(&self, state: i32) -> i32 {
        if state < 0 {
            FLAG_AIR
        } else {
            self.inp.flags[state as usize]
        }
    }

    fn block_index(&self, y: i32) -> usize {
        ((y - self.inp.min_y) as usize) * 256
            + (self.column_z as usize) * 16
            + self.column_x as usize
    }

    fn get_block(&self, y: i32) -> i32 {
        if y < self.inp.min_y || y > self.inp.max_y {
            -1
        } else {
            self.blocks[self.block_index(y)]
        }
    }

    fn column(&self) -> usize {
        self.column_z as usize * 16 + self.column_x as usize
    }

    fn is_opaque(&self, map: usize, state: i32) -> bool {
        let flags = self.flags_of(state);
        if map == 0 {
            flags & FLAG_BLOCKS_MOTION != 0
        } else {
            flags & FLAG_AIR == 0
        }
    }

    // One Heightmap.update for the current column.
    fn update_map(&mut self, map: usize, y: i32, state: i32) {
        let column = self.column();
        let first = if map == 0 {
            self.floor[column]
        } else {
            self.surface[column]
        };
        if y <= first - 2 {
            return;
        }
        let mut next = None;
        if self.is_opaque(map, state) {
            if y >= first {
                next = Some(y + 1);
            }
        } else if first - 1 == y {
            let mut found = self.inp.min_y;
            let mut m = y - 1;
            while m >= self.inp.min_y {
                if self.is_opaque(map, self.get_block(m)) {
                    found = m + 1;
                    break;
                }
                m -= 1;
            }
            next = Some(found);
        }
        if let Some(value) = next {
            if map == 0 {
                self.floor[column] = value;
            } else {
                self.surface[column] = value;
            }
        }
    }

    // BlockColumn.setBlock: write, update both heightmaps, mark fluids.
    fn set_block(&mut self, y: i32, state: i32) {
        if y < self.inp.min_y || y > self.inp.max_y {
            return;
        }
        let index = self.block_index(y);
        self.blocks[index] = state;
        self.update_map(0, y, state);
        self.update_map(1, y, state);
        if self.flags_of(state) & FLAG_FLUID != 0 {
            self.marks.push(index as i32);
        }
    }

    // zoom_biome for the current column, reusing the corner jitter across its quarts.
    fn column_biome(&mut self, y: i32) -> usize {
        let inp = self.inp;
        let i = self.block_x - 2;
        let j = y - 2;
        let k = self.block_z - 2;
        let l = i >> 2;
        let m = j >> 2;
        let n = k >> 2;
        let d = (i & 3) as f64 / 4.0;
        let e = (j & 3) as f64 / 4.0;
        let f = (k & 3) as f64 / 4.0;
        let base = ((inp.min_y - 2) >> 2) - 1;
        if m < base || (m + 1 - base) as usize >= self.corners.len() {
            return self.biome_at(self.block_x, y, self.block_z);
        }
        let min_quart = quart_index(inp.min_y);
        let max_quart = quart_index(inp.min_y) + quart_index(inp.max_y - inp.min_y + 1) - 1;
        let gx0 = l - (inp.chunk_x * 4 - 1);
        let gz0 = n - (inp.chunk_z * 4 - 1);
        let lo = (m.clamp(min_quart, max_quart) - min_quart) * GRID_QUARTS;
        let hi = ((m + 1).clamp(min_quart, max_quart) - min_quart) * GRID_QUARTS;
        let first = inp.biomes[((lo + gz0) * GRID_QUARTS + gx0) as usize];
        let uniform = [lo, hi].iter().all(|layer| {
            (0..2).all(|dz| {
                (0..2).all(|dx| {
                    inp.biomes[((layer + gz0 + dz) * GRID_QUARTS + gx0 + dx) as usize] == first
                })
            })
        });
        if uniform {
            return first as usize;
        }
        let mut best = 0;
        let mut distance = f64::INFINITY;
        for p in 0..8 {
            let bx = p & 4 == 0;
            let by = p & 2 == 0;
            let bz = p & 1 == 0;
            let r = if by { m } else { m + 1 };
            let slot = (r - base) as usize;
            if self.corner_stamp[slot] != self.column_stamp {
                for (c, corner) in self.corners[slot].iter_mut().enumerate() {
                    let q = if c & 2 == 0 { l } else { l + 1 };
                    let s = if c & 1 == 0 { n } else { n + 1 };
                    *corner = fiddles(inp.biome_seed, q, r, s);
                }
                self.corner_stamp[slot] = self.column_stamp;
            }
            let (g, h, o) = self.corners[slot][(!bx as usize) * 2 + !bz as usize];
            let a = if bz { f } else { f - 1.0 } + o;
            let b = if by { e } else { e - 1.0 } + h;
            let c = if bx { d } else { d - 1.0 } + g;
            let v = a * a + b * b + c * c;
            if distance > v {
                best = p;
                distance = v;
            }
        }
        let qx = if best & 4 == 0 { l } else { l + 1 };
        let qy = if best & 2 == 0 { m } else { m + 1 };
        let qz = if best & 1 == 0 { n } else { n + 1 };
        let qy = qy.clamp(min_quart, max_quart);
        let gx = qx - (inp.chunk_x * 4 - 1);
        let gz = qz - (inp.chunk_z * 4 - 1);
        let index = (((qy - min_quart) * GRID_QUARTS + gz) * GRID_QUARTS + gx) as usize;
        inp.biomes[index] as usize
    }

    fn biome_at(&self, x: i32, y: i32, z: i32) -> usize {
        zoom_biome(
            self.inp.biomes,
            self.inp.biome_seed,
            self.inp.chunk_x,
            self.inp.chunk_z,
            self.inp.min_y,
            self.inp.max_y,
            x,
            y,
            z,
        )
    }

    fn build_columns(&mut self) {
        let s = self.s;
        let min_x = self.inp.chunk_x * 16;
        let min_z = self.inp.chunk_z * 16;
        let min_y = self.inp.min_y;
        for k in 0..16 {
            for l in 0..16 {
                self.column_x = k;
                self.column_z = l;
                let m = min_x + k;
                let n = min_z + l;
                let o = self.surface[self.column()];
                let top = self.biome_at(m, if s.legacy { 0 } else { o }, n);
                let flags = s.biomes[top].flags;
                if flags & BIOME_FLAG_BADLANDS_EXTENSION != 0 {
                    self.eroded_badlands(m, n, o);
                }

                let p = self.surface[self.column()];
                self.update_xz(m, n);
                let mut q = 0;
                let mut r = i32::MIN;
                let mut stone_floor = i32::MAX;
                let mut u = p;
                while u >= min_y {
                    let state = self.get_block(u);
                    let state_flags = self.flags_of(state);
                    if state_flags & FLAG_AIR != 0 {
                        q = 0;
                        r = i32::MIN;
                    } else if state_flags & FLAG_FLUID != 0 {
                        if r == i32::MIN {
                            r = u + 1;
                        }
                    } else {
                        if stone_floor >= u {
                            stone_floor = WAY_BELOW_MIN_Y;
                            let mut v = u - 1;
                            while v >= min_y - 1 {
                                let below = self.flags_of(self.get_block(v));
                                if below & FLAG_AIR != 0 || below & FLAG_FLUID != 0 {
                                    stone_floor = v + 1;
                                    break;
                                }
                                v -= 1;
                            }
                        }
                        q += 1;
                        let below = u - stone_floor + 1;
                        self.update_y(q, below, r, u);
                        if state == s.default_block {
                            if let Some(replacement) = self.rule(s.root as i32, m, u, n) {
                                self.set_block(u, replacement);
                            }
                        }
                    }
                    u -= 1;
                }

                if flags & BIOME_FLAG_ICEBERG_EXTENSION != 0 {
                    let level = self.min_surface_level();
                    self.frozen_ocean(level, top, m, n, o);
                }
            }
        }
    }

    fn update_xz(&mut self, x: i32, z: i32) {
        self.block_x = x;
        self.block_z = z;
        self.column_stamp += 1;
        self.secondary = None;
        self.min_surface = None;
        self.surface_depth = self.surface_depth_at(x, z);
    }

    fn update_y(&mut self, above: i32, below: i32, water: i32, y: i32) {
        self.stone_above = above;
        self.stone_below = below;
        self.water_height = water;
        self.block_y = y;
        self.block_stamp += 1;
        self.biome = None;
    }

    fn surface_depth_at(&self, x: i32, z: i32) -> i32 {
        let d = self.s.noises[NOISE_SURFACE].get_value(x as f64, 0.0, z as f64);
        let mut random = self.s.noise_random.at(x, 0, z);
        (d * 2.75 + 3.0 + random.next_double() * 0.25) as i32
    }

    fn surface_secondary(&mut self) -> f64 {
        if let Some(value) = self.secondary {
            return value;
        }
        let value = self.s.noises[NOISE_SURFACE_SECONDARY].get_value(
            self.block_x as f64,
            0.0,
            self.block_z as f64,
        );
        self.secondary = Some(value);
        value
    }

    fn preliminary_level(&self, x: i32, z: i32) -> i32 {
        let value = self.s.tree.compute_root(PRELIMINARY_ROOT, x, 0, z);
        mth::floor(value.unwrap_or(0.0))
    }

    fn min_surface_level(&mut self) -> i32 {
        if let Some(value) = self.min_surface {
            return value;
        }
        let i = self.block_x >> 4;
        let j = self.block_z >> 4;
        let levels = match self.cell {
            Some((ci, cj, levels)) if ci == i && cj == j => levels,
            _ => {
                let levels = [
                    self.preliminary_level(i << 4, j << 4),
                    self.preliminary_level((i + 1) << 4, j << 4),
                    self.preliminary_level(i << 4, (j + 1) << 4),
                    self.preliminary_level((i + 1) << 4, (j + 1) << 4),
                ];
                self.cell = Some((i, j, levels));
                levels
            }
        };
        let k = mth::floor(mth::lerp2(
            ((self.block_x & 15) as f32 / 16.0f32) as f64,
            ((self.block_z & 15) as f32 / 16.0f32) as f64,
            levels[0] as f64,
            levels[1] as f64,
            levels[2] as f64,
            levels[3] as f64,
        ));
        let value = k.wrapping_add(self.surface_depth).wrapping_sub(8);
        self.min_surface = Some(value);
        value
    }

    fn current_biome(&mut self) -> usize {
        if let Some(biome) = self.biome {
            return biome;
        }
        let biome = self.column_biome(self.block_y);
        self.biome = Some(biome);
        biome
    }

    fn band(&self, x: i32, y: i32, z: i32) -> i32 {
        let offset = self.s.noises[NOISE_CLAY_BANDS_OFFSET].get_value(x as f64, 0.0, z as f64);
        let l = java_round(offset * 4.0) as i32;
        self.s.clay_bands
            [(y.wrapping_add(l).wrapping_add(BAND_COUNT as i32) % BAND_COUNT as i32) as usize]
    }

    fn rule(&mut self, id: i32, x: i32, y: i32, z: i32) -> Option<i32> {
        let s = self.s;
        let node = s.rules[id as usize];
        match node[0] {
            R_BLOCK => Some(node[1]),
            R_SEQUENCE => {
                let filter = s.seq_filter[id as usize];
                if filter != u32::MAX {
                    let biome = self.current_biome();
                    let (start, count) = s.seq_ranges[filter as usize + biome];
                    for i in start..start + count {
                        if let Some(state) = self.rule(s.seq_children[i as usize], x, y, z) {
                            return Some(state);
                        }
                    }
                    return None;
                }
                for i in 0..node[2] {
                    let child = s.lists[(node[1] + i) as usize];
                    if let Some(state) = self.rule(child, x, y, z) {
                        return Some(state);
                    }
                }
                None
            }
            R_TEST => {
                if self.cond(node[1]) {
                    self.rule(node[2], x, y, z)
                } else {
                    None
                }
            }
            R_BANDLANDS => Some(self.band(x, y, z)),
            R_BANDS => {
                let frequency = f32::from_bits(node[3] as u32);
                let noise = s.noises[NOISE_SURFACE_SECONDARY].get_value(
                    (x as f32 * frequency) as f64,
                    0.0,
                    (z as f32 * frequency) as f64,
                ) * node[4] as f64;
                let index = (y as i64 + java_round(noise)).rem_euclid(node[2] as i64 - 1);
                Some(s.lists[(node[1] as i64 + index) as usize])
            }
            R_GRID => {
                let child = s.lists[(node[1] + (x & 15) * 16 + (z & 15)) as usize];
                self.rule(child, x, y, z)
            }
            R_BY_BIOME => {
                let biome = self.current_biome();
                let target = s.lists[node[2] as usize + biome];
                if target >= 0 {
                    if let Some(state) = self.rule(target, x, y, z) {
                        return Some(state);
                    }
                }
                self.rule(node[1], x, y, z)
            }
            _ => None,
        }
    }

    fn column_cached(&mut self, id: i32, compute: impl FnOnce(&mut Self) -> bool) -> bool {
        let index = id as usize;
        if self.cond_stamp[index] == self.column_stamp {
            return self.cond_value[index];
        }
        let value = compute(self);
        self.cond_stamp[index] = self.column_stamp;
        self.cond_value[index] = value;
        value
    }

    fn cond(&mut self, id: i32) -> bool {
        let index = id as usize;
        if self.block_cond_stamp[index] == self.block_stamp {
            return self.block_cond_value[index];
        }
        let value = self.eval_cond(id);
        self.block_cond_stamp[index] = self.block_stamp;
        self.block_cond_value[index] = value;
        value
    }

    fn eval_cond(&mut self, id: i32) -> bool {
        let s = self.s;
        let node = s.conds[id as usize];
        match node[0] {
            C_BIOME => {
                let biome = self.current_biome();
                s.lists[node[1] as usize + biome] != 0
            }
            C_NOISE => {
                let [minimum, maximum] = s.cond_doubles[id as usize];
                self.column_cached(id, |run| {
                    let d = s.noises[node[1] as usize].get_value(
                        run.block_x as f64,
                        0.0,
                        run.block_z as f64,
                    );
                    d >= minimum && d <= maximum
                })
            }
            C_GRADIENT => {
                let y = self.block_y;
                if y <= node[2] {
                    true
                } else if y >= node[3] {
                    false
                } else {
                    let d = mth::map(y as f64, node[2] as f64, node[3] as f64, 1.0, 0.0);
                    let mut random =
                        s.gradients[node[1] as usize].at(self.block_x, y, self.block_z);
                    (random.next_float() as f64) < d
                }
            }
            C_Y => {
                let stone = if node[3] != 0 { self.stone_above } else { 0 };
                self.block_y.wrapping_add(stone)
                    >= node[1].wrapping_add(self.surface_depth.wrapping_mul(node[2]))
            }
            C_WATER => {
                let stone = if node[3] != 0 { self.stone_above } else { 0 };
                self.water_height == i32::MIN
                    || self.block_y.wrapping_add(stone)
                        >= self
                            .water_height
                            .wrapping_add(node[1])
                            .wrapping_add(self.surface_depth.wrapping_mul(node[2]))
            }
            C_TEMPERATURE => {
                let biome = self.current_biome();
                s.cold_enough_to_snow(biome, self.block_x, self.block_y, self.block_z)
            }
            C_STEEP => self.column_cached(id, |run| run.steep()),
            C_HOLE => self.surface_depth <= 0,
            C_ABOVE_PRELIMINARY => self.block_y >= self.min_surface_level(),
            C_STONE_DEPTH => {
                let depth = if node[4] != 0 {
                    self.stone_below
                } else {
                    self.stone_above
                };
                let surface = if node[2] != 0 { self.surface_depth } else { 0 };
                let secondary = if node[3] == 0 {
                    0
                } else {
                    mth::map(self.surface_secondary(), -1.0, 1.0, 0.0, node[3] as f64) as i32
                };
                depth
                    <= 1i32
                        .wrapping_add(node[1])
                        .wrapping_add(surface)
                        .wrapping_add(secondary)
            }
            C_NOT => !self.cond(node[1]),
            C_ALL => {
                for i in 0..node[2] {
                    if !self.cond(s.lists[(node[1] + i) as usize]) {
                        return false;
                    }
                }
                true
            }
            C_ANY => {
                for i in 0..node[2] {
                    if self.cond(s.lists[(node[1] + i) as usize]) {
                        return true;
                    }
                }
                false
            }
            C_SLOPE => self.column_cached(id, |run| {
                let (north, south, west, east) = run.neighbour_heights();
                let spread =
                    north.max(south).max(east.max(west)) - north.min(south).min(east.min(west));
                spread >= node[1] && spread <= node[2]
            }),
            _ => false,
        }
    }

    // WORLD_SURFACE_WG heights north, south, west and east of the column, clamped to the chunk.
    fn neighbour_heights(&self) -> (i32, i32, i32, i32) {
        let x = self.block_x & 15;
        let z = self.block_z & 15;
        let north = (z - 1).max(0);
        let south = (z + 1).min(15);
        let west = (x - 1).max(0);
        let east = (x + 1).min(15);
        let at = |px: i32, pz: i32| self.surface[(px + pz * 16) as usize] - 1;
        (at(x, north), at(x, south), at(west, z), at(east, z))
    }

    fn steep(&self) -> bool {
        let x = self.block_x & 15;
        let z = self.block_z & 15;
        let north = (z - 1).max(0);
        let south = (z + 1).min(15);
        let m = self.surface[(x + north * 16) as usize] - 1;
        let n = self.surface[(x + south * 16) as usize] - 1;
        if n >= m + 4 {
            return true;
        }
        let west = (x - 1).max(0);
        let east = (x + 1).min(15);
        let q = self.surface[(west + z * 16) as usize] - 1;
        let r = self.surface[(east + z * 16) as usize] - 1;
        q >= r + 4
    }

    fn eroded_badlands(&mut self, x: i32, z: i32, top: i32) {
        let s = self.s;
        let noises = &s.noises;
        let e = mth::java_min(
            (noises[NOISE_BADLANDS_SURFACE].get_value(x as f64, 0.0, z as f64) * 8.25).abs(),
            noises[NOISE_BADLANDS_PILLAR].get_value(x as f64 * 0.2, 0.0, z as f64 * 0.2) * 15.0,
        );
        if e <= 0.0 {
            return;
        }
        let h =
            (noises[NOISE_BADLANDS_PILLAR_ROOF].get_value(x as f64 * 0.75, 0.0, z as f64 * 0.75)
                * 1.5)
                .abs();
        let l = 64.0 + mth::java_min(e * e * 2.5, (h * 50.0).ceil() + 24.0);
        let m = mth::floor(l);
        if top > m {
            return;
        }
        let mut n = m;
        while n >= self.inp.min_y {
            let flags = self.flags_of(self.get_block(n));
            if flags & FLAG_DEFAULT_BLOCK != 0 {
                break;
            }
            if flags & FLAG_WATER != 0 {
                return;
            }
            n -= 1;
        }
        let mut n = m;
        while n >= self.inp.min_y && self.flags_of(self.get_block(n)) & FLAG_AIR != 0 {
            self.set_block(n, s.default_block);
            n -= 1;
        }
    }

    fn frozen_ocean(&mut self, lowest: i32, biome: usize, x: i32, z: i32, top: i32) {
        let s = self.s;
        let noises = &s.noises;
        let e = mth::java_min(
            (noises[NOISE_ICEBERG_SURFACE].get_value(x as f64, 0.0, z as f64) * 8.25).abs(),
            noises[NOISE_ICEBERG_PILLAR].get_value(x as f64 * 1.28, 0.0, z as f64 * 1.28) * 15.0,
        );
        if e <= 1.8 {
            return;
        }
        let h =
            (noises[NOISE_ICEBERG_PILLAR_ROOF].get_value(x as f64 * 1.17, 0.0, z as f64 * 1.17)
                * 1.5)
                .abs();
        let sea = s.sea_level;
        let mut m = mth::java_min(e * e * 1.2, (h * 40.0).ceil() + 14.0);
        if s.temperature(biome, x, sea, z) > 0.1f32 {
            m -= 2.0;
        }
        let n;
        if m > 2.0 {
            n = sea as f64 - m - 7.0;
            m += sea as f64;
        } else {
            m = 0.0;
            n = 0.0;
        }
        let o = m;
        let mut random = s.noise_random.at(x, 0, z);
        let p = 2 + random.next_int_bound(4);
        let q = sea + 18 + random.next_int_bound(10);
        let mut r = 0;
        let mut y = top.max(o as i32 + 1);
        while y >= lowest {
            let air_branch = self.flags_of(self.get_block(y)) & FLAG_AIR != 0
                && y < o as i32
                && random.next_double() > 0.01;
            let water_branch = !air_branch
                && self.flags_of(self.get_block(y)) & FLAG_WATER != 0
                && y > n as i32
                && y < sea
                && n != 0.0
                && random.next_double() > 0.15;
            if air_branch || water_branch {
                if r <= p && y > q {
                    self.set_block(y, STATE_SNOW_BLOCK);
                    r += 1;
                } else {
                    self.set_block(y, STATE_PACKED_ICE);
                }
            }
            y -= 1;
        }
    }
}

// Math.round for doubles, as a long.
fn java_round(value: f64) -> i64 {
    let floor = value.floor();
    if value - floor >= 0.5 {
        floor as i64 + 1
    } else {
        floor as i64
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn java_round_rounds_half_up() {
        assert_eq!(java_round(2.5), 3);
        assert_eq!(java_round(-2.5), -2);
        assert_eq!(java_round(-0.5), 0);
        assert_eq!(java_round(0.49999999999999994), 0);
        assert_eq!(java_round(-3.2), -3);
    }

    #[test]
    fn clay_bands_use_every_state_and_are_deterministic() {
        let a = Root::new(5, false).clay_bands();
        let b = Root::new(5, false).clay_bands();
        assert_eq!(a, b);
        for state in [
            STATE_WHITE_TERRACOTTA,
            STATE_ORANGE_TERRACOTTA,
            STATE_TERRACOTTA,
            STATE_YELLOW_TERRACOTTA,
            STATE_BROWN_TERRACOTTA,
            STATE_RED_TERRACOTTA,
            STATE_LIGHT_GRAY_TERRACOTTA,
        ] {
            assert!(a.contains(&state), "band state {state} missing");
        }
        assert_ne!(a, Root::new(6, false).clay_bands());
    }

    #[test]
    fn zoom_returns_the_grid_biome_for_uniform_grids() {
        let grid = vec![7; (GRID_QUARTS * GRID_QUARTS * 96) as usize];
        for x in 0..16 {
            for z in 0..16 {
                assert_eq!(zoom_biome(&grid, 99, 0, 0, -64, 319, x, 70, z), 7);
                assert_eq!(zoom_biome(&grid, 99, 0, 0, -64, 319, x, 400, z), 7);
            }
        }
    }
}
