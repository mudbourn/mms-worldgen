use crate::fixture;
use crate::surface::{
    Input, Surface, SurfaceSpec, C_ABOVE_PRELIMINARY, C_ALL, C_ANY, C_BIOME, C_GRADIENT, C_HOLE,
    C_NOISE, C_NOT, C_SLOPE, C_STEEP, C_STONE_DEPTH, C_TEMPERATURE, C_WATER, C_Y, FIXED_STATES,
    FLAG_AIR, FLAG_BLOCKS_MOTION, FLAG_DEFAULT_BLOCK, FLAG_FLUID, FLAG_WATER, R_BANDLANDS, R_BANDS,
    R_BLOCK, R_BY_BIOME, R_GRID, R_SEQUENCE, R_TEST,
};
use crate::tree::Tree;
use std::sync::Arc;

const AIR: i32 = FIXED_STATES as i32;
const STONE: i32 = AIR + 1;
const WATER: i32 = AIR + 2;
const LAVA: i32 = AIR + 3;
const ORE_BASE: i32 = AIR + 4;
const GRASS: i32 = AIR + 10;
const DIRT: i32 = AIR + 11;
const SAND: i32 = AIR + 12;
const GRAVEL: i32 = AIR + 13;
const BEDROCK: i32 = AIR + 14;
const SNOW: i32 = AIR + 15;
const STATE_COUNT: usize = AIR as usize + 16;

const PLAINS: i32 = 0;
const DESERT: i32 = 1;
const FROZEN_OCEAN: i32 = 2;
const ERODED_BADLANDS: i32 = 3;
const BIOME_COUNT: usize = 4;

const MIN_Y: i32 = -64;
const MAX_Y: i32 = 319;
const SEA_LEVEL: i32 = 63;
const BIOME_SEED: i64 = 0x1f2e3d4c5b6a7988;

// Builds a flat surface spec the way the Java translator lays one out.
#[derive(Default)]
struct Builder {
    spec: SurfaceSpec,
}

impl Builder {
    fn rule(&mut self, ints: [i32; 5]) -> i32 {
        self.spec.rule_ints.extend(ints);
        (self.spec.rule_ints.len() / 5) as i32 - 1
    }

    fn cond(&mut self, ints: [i32; 5], doubles: [f64; 2]) -> i32 {
        self.spec.cond_ints.extend(ints);
        self.spec.cond_doubles.extend(doubles);
        (self.spec.cond_ints.len() / 5) as i32 - 1
    }

    fn list(&mut self, values: &[i32]) -> i32 {
        let start = self.spec.lists.len() as i32;
        self.spec.lists.extend_from_slice(values);
        start
    }

    fn block(&mut self, state: i32) -> i32 {
        self.rule([R_BLOCK, state, 0, 0, 0])
    }

    fn sequence(&mut self, children: &[i32]) -> i32 {
        let start = self.list(children);
        self.rule([R_SEQUENCE, start, children.len() as i32, 0, 0])
    }

    fn test(&mut self, cond: i32, rule: i32) -> i32 {
        self.rule([R_TEST, cond, rule, 0, 0])
    }

    fn biome(&mut self, biomes: &[i32]) -> i32 {
        let flags: Vec<i32> = (0..BIOME_COUNT as i32)
            .map(|b| biomes.contains(&b) as i32)
            .collect();
        let start = self.list(&flags);
        self.cond([C_BIOME, start, 0, 0, 0], [0.0; 2])
    }

    fn simple(&mut self, kind: i32, a: i32, b: i32, c: i32, d: i32) -> i32 {
        self.cond([kind, a, b, c, d], [0.0; 2])
    }
}

fn noise_tables() -> (Vec<String>, Vec<i32>, Vec<i32>, Vec<f64>) {
    let rows: [(&str, i32, &[f64]); 11] = [
        ("minecraft:surface", -6, &[1.0, 1.0, 1.0]),
        ("minecraft:surface_secondary", -6, &[1.0, 1.0, 0.0, 1.0]),
        ("minecraft:clay_bands_offset", -8, &[1.0]),
        ("minecraft:badlands_pillar", -2, &[1.0, 1.0, 1.0, 1.0]),
        ("minecraft:badlands_pillar_roof", -8, &[1.0]),
        ("minecraft:badlands_surface", -6, &[1.0, 1.0, 1.0]),
        ("minecraft:iceberg_pillar", -6, &[1.0, 1.0, 1.0, 1.0]),
        ("minecraft:iceberg_pillar_roof", -3, &[1.0]),
        ("minecraft:iceberg_surface", -6, &[1.0, 1.0, 1.0]),
        ("minecraft:gravel", -8, &[1.0, 1.0, 1.0, 1.0]),
        ("minecraft:calcite", -9, &[1.0, 1.0, 1.0, 1.0]),
    ];
    let mut keys = Vec::new();
    let mut octaves = Vec::new();
    let mut ends = Vec::new();
    let mut amps = Vec::new();
    for (key, octave, values) in rows {
        keys.push(key.to_string());
        octaves.push(octave);
        amps.extend_from_slice(values);
        ends.push(amps.len() as i32);
    }
    (keys, octaves, ends, amps)
}

// A rule tree touching every rule and condition kind over four biomes.
fn surface(seed: i64) -> Surface {
    let mut b = Builder::default();
    let bedrock = b.block(BEDROCK);
    let bedrock_gradient = b.simple(C_GRADIENT, 0, MIN_Y, MIN_Y + 5, 0);
    let bedrock_rule = b.test(bedrock_gradient, bedrock);

    let on_floor = b.simple(C_STONE_DEPTH, 0, 0, 0, 0);
    let under_floor = b.simple(C_STONE_DEPTH, 0, 1, 0, 0);
    let deep_under = b.simple(C_STONE_DEPTH, 0, 1, 6, 0);
    let under_ceiling = b.simple(C_STONE_DEPTH, 0, 1, 0, 1);
    let water_here = b.simple(C_WATER, -1, 0, 0, 0);
    let water_deep = b.simple(C_WATER, 0, -6, 1, 0);
    let above = b.simple(C_ABOVE_PRELIMINARY, 0, 0, 0, 0);
    let steep = b.simple(C_STEEP, 0, 0, 0, 0);
    let hole = b.simple(C_HOLE, 0, 0, 0, 0);
    let cold = b.simple(C_TEMPERATURE, 0, 0, 0, 0);
    let y_sea = b.simple(C_Y, SEA_LEVEL - 4, 0, 0, 0);
    let y_high = b.simple(C_Y, 100, 1, 1, 0);
    let gravel_noise = b.cond([C_NOISE, 9, 0, 0, 0], [-0.12, 0.12]);
    let calcite_noise = b.cond([C_NOISE, 10, 0, 0, 0], [0.0, f64::MAX]);
    let slope = b.simple(C_SLOPE, 3, i32::MAX, 0, 0);

    let desert = b.biome(&[DESERT]);
    let badlands = b.biome(&[ERODED_BADLANDS]);
    let frozen = b.biome(&[FROZEN_OCEAN]);
    let plains_desert = b.biome(&[PLAINS, DESERT]);

    let stone = b.block(STONE);
    let grass = b.block(GRASS);
    let dirt = b.block(DIRT);
    let sand = b.block(SAND);
    let gravel = b.block(GRAVEL);
    let snow = b.block(SNOW);
    let bands = b.rule([R_BANDLANDS, 0, 0, 0, 0]);

    let gravel_rule = b.test(gravel_noise, gravel);
    let not_water = {
        let negated = b.simple(C_NOT, water_here, 0, 0, 0);
        let any_water = b.simple(C_NOT, negated, 0, 0, 0);
        let list = b.list(&[any_water, on_floor]);
        b.simple(C_ALL, list, 2, 0, 0)
    };

    let desert_top = {
        let first = b.test(on_floor, sand);
        let second = b.test(under_floor, sand);
        let layers = b.sequence(&[first, second]);
        b.test(desert, layers)
    };
    let badlands_rule = {
        let top = b.test(above, bands);
        let deep = b.test(deep_under, bands);
        let inner = b.sequence(&[top, deep]);
        b.test(badlands, inner)
    };
    let frozen_rule = {
        let list = b.list(&[cold, on_floor]);
        let both = b.simple(C_ALL, list, 2, 0, 0);
        let top = b.test(both, snow);
        b.test(frozen, top)
    };
    let steep_rule = {
        let any_list = b.list(&[steep, slope]);
        let any = b.simple(C_ANY, any_list, 2, 0, 0);
        let inner = b.test(any, stone);
        b.test(on_floor, inner)
    };
    let grass_rule = {
        let inner = b.test(not_water, grass);
        let under = b.test(under_floor, dirt);
        let seq = b.sequence(&[inner, under]);
        b.test(plains_desert, seq)
    };
    let beach = {
        let near_sea = b.test(y_sea, sand);
        let gate = b.test(water_deep, near_sea);
        b.test(under_ceiling, gate)
    };
    let hole_guard = b.test(hole, gravel_rule);
    let high_cap = b.test(y_high, snow);
    let calcite_cap = b.test(calcite_noise, stone);

    let grid_children: Vec<i32> = (0..256)
        .map(|i| if i % 2 == 0 { sand } else { dirt })
        .collect();
    let grid_start = b.list(&grid_children);
    let grid = b.rule([R_GRID, grid_start, 0, 0, 0]);
    let banded = {
        let states = b.list(&[SAND, DIRT, GRAVEL]);
        b.rule([R_BANDS, states, 3, 0.05f32.to_bits() as i32, 4])
    };
    let by_biome = {
        let targets: Vec<i32> = (0..BIOME_COUNT as i32)
            .map(|i| if i == DESERT { banded } else { -1 })
            .collect();
        let start = b.list(&targets);
        b.rule([R_BY_BIOME, grid, start, 0, 0])
    };
    let by_biome_rule = b.test(deep_under, by_biome);

    let surface_layer = b.sequence(&[
        desert_top,
        badlands_rule,
        frozen_rule,
        steep_rule,
        grass_rule,
        beach,
        hole_guard,
        high_cap,
        calcite_cap,
        by_biome_rule,
    ]);
    let top = b.test(above, surface_layer);
    let root = b.sequence(&[bedrock_rule, top]);

    let (keys, octaves, ends, amps) = noise_tables();
    let mut spec = b.spec;
    spec.seed = 0;
    spec.legacy = false;
    spec.sea_level = SEA_LEVEL;
    spec.default_block = STONE;
    spec.root = root;
    spec.noise_keys = keys;
    spec.noise_octaves = octaves;
    spec.noise_amp_ends = ends;
    spec.noise_amps = amps;
    spec.gradient_names = vec!["minecraft:bedrock_floor".to_string()];
    spec.biome_ints = vec![
        0.8f32.to_bits() as i32,
        0,
        0,
        2.0f32.to_bits() as i32,
        0,
        0,
        0.0f32.to_bits() as i32,
        1,
        2,
        2.0f32.to_bits() as i32,
        0,
        1,
    ];
    spec.seed = seed;
    let tree = Arc::new(Tree::build(&fixture::spec(seed)).unwrap());
    Surface::new(tree, &spec).unwrap()
}

fn palette_flags() -> Vec<i32> {
    let mut flags = vec![0; STATE_COUNT];
    for state in flags.iter_mut().take(FIXED_STATES) {
        *state = FLAG_BLOCKS_MOTION;
    }
    flags[AIR as usize] = FLAG_AIR;
    flags[STONE as usize] = FLAG_BLOCKS_MOTION | FLAG_DEFAULT_BLOCK;
    flags[WATER as usize] = FLAG_FLUID | FLAG_WATER;
    flags[LAVA as usize] = FLAG_FLUID;
    for state in ORE_BASE..GRASS {
        flags[state as usize] = FLAG_BLOCKS_MOTION;
    }
    for state in GRASS..STATE_COUNT as i32 {
        flags[state as usize] = FLAG_BLOCKS_MOTION;
    }
    flags[SNOW as usize] = FLAG_BLOCKS_MOTION;
    flags
}

// Quart grid of the six by six neighbourhood with biomes that vary in blobs.
fn biome_grid(chunk_x: i32, chunk_z: i32) -> Vec<i32> {
    let quarts = ((MAX_Y - MIN_Y + 1) / 4) as usize;
    let mut grid = Vec::with_capacity(36 * quarts);
    for qy in 0..quarts as i32 {
        for qz in 0..6 {
            for qx in 0..6 {
                let x = chunk_x * 4 - 1 + qx;
                let z = chunk_z * 4 - 1 + qz;
                let blob =
                    (x.div_euclid(5) * 7 + z.div_euclid(6) * 13 + qy.div_euclid(40)).rem_euclid(9);
                grid.push(match blob {
                    0..=3 => PLAINS,
                    4 | 5 => DESERT,
                    6 | 7 => FROZEN_OCEAN,
                    _ => ERODED_BADLANDS,
                });
            }
        }
    }
    grid
}

// Runs the Rust NOISE stage and shifts its palette to the surface palette.
fn noise_chunk(seed: i64, chunk_x: i32, chunk_z: i32) -> (Vec<i32>, Vec<i32>) {
    let stage = fixture::stage(seed, 0);
    let result = stage.fill(chunk_x, chunk_z, MIN_Y, MAX_Y, &[0]).unwrap();
    let blocks_end = 512 + 256 * (MAX_Y - MIN_Y + 1) as usize;
    let maps = result[..512].to_vec();
    let blocks = result[512..blocks_end]
        .iter()
        .map(|state| match *state {
            0 => AIR,
            1 => STONE,
            2 => WATER,
            3 => LAVA,
            other => ORE_BASE + other - 4,
        })
        .collect();
    (maps, blocks)
}

pub fn build(seed: i64, chunk_x: i32, chunk_z: i32) -> Vec<i32> {
    let surface = surface(seed);
    let (maps, blocks) = noise_chunk(seed, chunk_x, chunk_z);
    let flags = palette_flags();
    let grid = biome_grid(chunk_x, chunk_z);
    surface
        .build(&Input {
            chunk_x,
            chunk_z,
            min_y: MIN_Y,
            max_y: MAX_Y,
            biome_seed: BIOME_SEED ^ seed,
            maps: &maps,
            blocks: &blocks,
            flags: &flags,
            biomes: &grid,
        })
        .unwrap()
}

pub fn hashes(seed: i64) -> Vec<(String, u64)> {
    fixture::chunks(seed)
        .iter()
        .take(10)
        .map(|&(cx, cz)| {
            (
                format!("surface {seed} {cx} {cz}"),
                fixture::hash(&build(seed, cx, cz)),
            )
        })
        .collect()
}

#[test]
fn surface_changes_the_top_layers_and_keeps_heightmaps_consistent() {
    let (maps, blocks) = noise_chunk(0, 0, 0);
    let result = build(0, 0, 0);
    let changes = result[512] as usize;
    assert!(changes > 1000, "only {changes} blocks changed");
    let mut after = blocks.clone();
    for pair in result[513..513 + changes * 2].chunks_exact(2) {
        after[pair[0] as usize] = pair[1];
    }
    let bedrock = after.iter().filter(|s| **s == BEDROCK).count();
    assert!(bedrock > 256, "bedrock floor missing: {bedrock}");
    for column in 0..256usize {
        let top = result[256 + column];
        assert!(top >= maps[256 + column]);
        let solid = (0..(MAX_Y - MIN_Y + 1) as usize)
            .rev()
            .find(|y| after[y * 256 + column] != AIR)
            .map_or(MIN_Y, |y| y as i32 + MIN_Y + 1);
        assert_eq!(top, solid, "world surface of column {column}");
    }
}

#[test]
fn only_default_block_positions_are_rewritten_unless_an_extension_runs() {
    let (_, blocks) = noise_chunk(1234567890123, 3, -2);
    let result = build(1234567890123, 3, -2);
    let changes = result[512] as usize;
    for pair in result[513..513 + changes * 2].chunks_exact(2) {
        let before = blocks[pair[0] as usize];
        assert!(
            before == STONE || before == AIR || before == WATER,
            "state {before} replaced by {}",
            pair[1]
        );
    }
}

#[test]
#[ignore]
fn bench_surface_chunk() {
    let surface = surface(0);
    let flags = palette_flags();
    let chunks: Vec<(i32, i32, Vec<i32>, Vec<i32>, Vec<i32>)> = (0..16)
        .map(|i| {
            let (cx, cz) = (200 + i % 4 * 3, 200 + i / 4 * 3);
            let (maps, blocks) = noise_chunk(0, cx, cz);
            (cx, cz, maps, blocks, biome_grid(cx, cz))
        })
        .collect();
    let run = || {
        for (cx, cz, maps, blocks, grid) in &chunks {
            let input = Input {
                chunk_x: *cx,
                chunk_z: *cz,
                min_y: MIN_Y,
                max_y: MAX_Y,
                biome_seed: BIOME_SEED,
                maps,
                blocks,
                flags: &flags,
                biomes: grid,
            };
            std::hint::black_box(surface.build(&input).unwrap());
        }
    };
    run();
    let start = std::time::Instant::now();
    for _ in 0..10 {
        run();
    }
    let ms = start.elapsed().as_secs_f64() * 1000.0 / (10 * chunks.len()) as f64;
    println!(
        "surface {ms:.2} ms per chunk over {} chunks",
        10 * chunks.len()
    );
}
