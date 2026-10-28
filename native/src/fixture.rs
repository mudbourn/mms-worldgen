use crate::stage::Stage;
use crate::tree::{Spec, Tree};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Instant;

const ROUTER: &str = include_str!("../testdata/overworld_router.txt");
const GOLDEN: &str = include_str!("../testdata/golden.txt");

// Stage settings: min y, height, cell sizes, sea level, aquifers, veins, roles, c2me order.
const VARIANTS: [[i32; 18]; 5] = [
    [-64, 384, 1, 2, 63, 1, 1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0],
    [-64, 384, 1, 2, 63, 1, 1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 1],
    [-64, 384, 1, 2, 63, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0],
    [-64, 384, 2, 1, 63, 1, 1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0],
    [-64, 384, 4, 4, 63, 1, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 1],
];
const FLAGS: [i32; 10] = [1, 2, 8, 4, 2, 2, 2, 2, 2, 2];
const SEEDS: [i64; 3] = [0, 1234567890123, -987654321];
const CHUNK_COUNT: usize = 20;

fn fields(name: &str) -> Vec<&'static str> {
    ROUTER
        .lines()
        .find(|l| l.split(' ').next() == Some(name))
        .map(|l| l.split(' ').skip(1).collect())
        .unwrap_or_default()
}

fn ints(name: &str) -> Vec<i32> {
    fields(name).iter().map(|v| v.parse().unwrap()).collect()
}

fn doubles(name: &str) -> Vec<f64> {
    fields(name)
        .iter()
        .map(|v| f64::from_bits(u64::from_str_radix(v, 16).unwrap()))
        .collect()
}

pub fn spec(seed: i64) -> Spec {
    Spec {
        seed,
        legacy: false,
        node_ints: ints("node_ints"),
        node_doubles: doubles("node_doubles"),
        noise_keys: fields("noise_keys").iter().map(|k| k.to_string()).collect(),
        noise_octaves: ints("noise_octaves"),
        noise_amp_ends: ints("noise_amp_ends"),
        noise_amps: doubles("noise_amps"),
        blended: doubles("blended"),
        spline_ints: ints("spline_ints"),
        spline_consts: doubles("spline_consts"),
        point_children: ints("point_children"),
        point_values: doubles("point_values"),
        roots: ints("roots"),
    }
}

pub fn stage(seed: i64, variant: usize) -> Stage {
    let tree = Arc::new(Tree::build(&spec(seed)).unwrap());
    Stage::new(tree, seed, false, &VARIANTS[variant], FLAGS.to_vec()).unwrap()
}

// Chunk positions spread over land, ocean and mountains, the first few near the origin.
fn chunks(seed: i64) -> Vec<(i32, i32)> {
    let mut state = seed as u64 ^ 0xabcdef;
    let mut list = vec![(0, 0), (1, 0), (-1, -1)];
    while list.len() < CHUNK_COUNT {
        let x = (splitmix(&mut state) % 6000) as i32 - 3000;
        let z = (splitmix(&mut state) % 6000) as i32 - 3000;
        list.push((x, z));
    }
    list
}

// Header, box, one bury piece, one box-beard piece and one junction around chunk (0, 0).
fn beard_ints(chunk_x: i32, chunk_z: i32) -> Vec<i32> {
    let x = chunk_x * 16;
    let z = chunk_z * 16;
    vec![
        1,
        x - 8,
        40,
        z - 8,
        x + 24,
        120,
        z + 24,
        2,
        1,
        x + 2,
        60,
        z + 3,
        x + 12,
        80,
        z + 11,
        1,
        0,
        x - 4,
        62,
        z + 5,
        x + 6,
        90,
        z + 18,
        2,
        -2,
        x + 5,
        64,
        z + 9,
    ]
}

fn hash(values: &[i32]) -> u64 {
    let mut h: u64 = 0xcbf29ce484222325;
    for v in values {
        h ^= *v as u32 as u64;
        h = h.wrapping_mul(0x100000001b3);
    }
    h
}

fn hash_f64(values: &[f64]) -> u64 {
    let mut h: u64 = 0xcbf29ce484222325;
    for v in values {
        h ^= v.to_bits();
        h = h.wrapping_mul(0x100000001b3);
    }
    h
}

fn splitmix(state: &mut u64) -> u64 {
    *state = state.wrapping_add(0x9e3779b97f4a7c15);
    let mut z = *state;
    z = (z ^ (z >> 30)).wrapping_mul(0xbf58476d1ce4e5b9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94d049bb133111eb);
    z ^ (z >> 31)
}

fn tree_hashes(seed: i64) -> Vec<(String, u64)> {
    let tree = Tree::build(&spec(seed)).unwrap();
    let mut state = seed as u64 ^ 0x1234;
    let mut points = Vec::new();
    for _ in 0..400 {
        let x = (splitmix(&mut state) % 4000) as i32 - 2000;
        let y = (splitmix(&mut state) % 400) as i32 - 80;
        let z = (splitmix(&mut state) % 4000) as i32 - 2000;
        points.push((x, y, z));
    }
    (0..tree.root_count())
        .map(|root| {
            let values: Vec<f64> = points
                .iter()
                .map(|p| tree.compute_root(root, p.0, p.1, p.2).unwrap())
                .collect();
            (format!("tree {seed} {root}"), hash_f64(&values))
        })
        .collect()
}

const BLOCK_INTS: usize = 512 + 256 * 384;

fn fill_hashes(seed: i64) -> Vec<(String, u64)> {
    let mut all = Vec::new();
    for variant in 0..VARIANTS.len() {
        let stage = stage(seed, variant);
        for (n, &(cx, cz)) in chunks(seed).iter().enumerate() {
            let plain = stage.fill(cx, cz, -64, 319, &[0]).unwrap();
            let (blocks, marks) = plain.split_at(BLOCK_INTS);
            all.push((
                format!("fill {seed} v{variant} {cx} {cz} plain"),
                hash(blocks),
            ));
            all.push((
                format!("fill {seed} v{variant} {cx} {cz} marks"),
                hash(marks),
            ));
            if n % 3 == 0 {
                let beard = stage.fill(cx, cz, -64, 319, &beard_ints(cx, cz)).unwrap();
                all.push((
                    format!("fill {seed} v{variant} {cx} {cz} beard"),
                    hash(&beard[..BLOCK_INTS]),
                ));
            }
        }
    }
    all
}

fn all_hashes() -> Vec<(String, u64)> {
    let mut all = Vec::new();
    for seed in SEEDS {
        all.extend(tree_hashes(seed));
        all.extend(fill_hashes(seed));
    }
    all
}

#[test]
fn outputs_match_golden_hashes() {
    let hashes = all_hashes();
    if std::env::var("WRITE_GOLDEN").is_ok() {
        let text: String = hashes
            .iter()
            .map(|(k, v)| format!("{k} = {v:x}\n"))
            .collect();
        std::fs::write(
            concat!(env!("CARGO_MANIFEST_DIR"), "/testdata/golden.txt"),
            text,
        )
        .unwrap();
        return;
    }
    let golden: HashMap<&str, &str> = GOLDEN.lines().filter_map(|l| l.split_once(" = ")).collect();
    assert_eq!(golden.len(), hashes.len());
    for (key, value) in hashes {
        assert_eq!(
            golden.get(key.as_str()).copied(),
            Some(format!("{value:x}").as_str()),
            "{key}"
        );
    }
}

#[test]
fn marks_are_sorted_fluid_blocks_and_present_with_aquifers() {
    let stage = stage(0, 0);
    let mut flagged = 0;
    for &(cx, cz) in chunks(0).iter() {
        let result = stage.fill(cx, cz, -64, 319, &[0]).unwrap();
        let (blocks, marks) = result.split_at(BLOCK_INTS);
        assert_eq!(marks[0] as usize, marks.len() - 1);
        assert!(marks[1..].windows(2).all(|pair| pair[0] < pair[1]));
        for &index in &marks[1..] {
            let state = blocks[512 + index as usize];
            assert!(state == 2 || state == 3, "mark on palette {state}");
        }
        flagged += marks[0];
    }
    assert!(flagged > 0);
}

#[test]
fn marks_are_empty_without_aquifers() {
    let stage = stage(0, 2);
    let (cx, cz) = chunks(0)[0];
    let result = stage.fill(cx, cz, -64, 319, &[0]).unwrap();
    assert_eq!(result[BLOCK_INTS], 0);
}

#[test]
#[ignore]
fn bench_fill_chunk() {
    let stage = stage(0, 0);
    let grid: Vec<(i32, i32)> = (0..8)
        .flat_map(|x| (0..8).map(move |z| (x * 3, z * 3)))
        .collect();
    for &(cx, cz) in grid.iter().take(4) {
        std::hint::black_box(stage.fill(cx, cz, -64, 319, &[0]).unwrap());
    }
    let rounds: usize = std::env::var("BENCH_ROUNDS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(1);
    let start = Instant::now();
    for round in 0..rounds as i32 {
        for &(cx, cz) in &grid {
            let result = stage.fill(cx + 200 + round * 30, cz + 200, -64, 319, &[0]);
            std::hint::black_box(result.unwrap());
        }
    }
    let count = grid.len() * rounds;
    let ms = start.elapsed().as_secs_f64() * 1000.0 / count as f64;
    println!("fillChunk {ms:.2} ms per chunk over {count} chunks");
}

#[test]
#[ignore]
fn bench_improved_noise() {
    use crate::noise::ImprovedNoise;
    use crate::random::XoroshiroRandomSource;
    let mut random = XoroshiroRandomSource::new(5);
    let noise = ImprovedNoise::new(&mut random);
    let mut state = 77u64;
    let points: Vec<[f64; 3]> = (0..4096)
        .map(|_| {
            [
                (splitmix(&mut state) % 100000) as f64 / 37.0,
                (splitmix(&mut state) % 100000) as f64 / 91.0,
                (splitmix(&mut state) % 100000) as f64 / 53.0,
            ]
        })
        .collect();
    let start = Instant::now();
    let mut sum = 0.0;
    for _ in 0..2000 {
        for p in &points {
            sum += noise.noise_scaled(p[0], p[1], p[2], 0.0, 0.0);
        }
    }
    let ns = start.elapsed().as_secs_f64() * 1e9 / (2000.0 * 4096.0);
    println!("improved {ns:.2} ns per call ({sum})");
    let start = Instant::now();
    for _ in 0..2000 {
        for p in &points {
            sum += noise.noise_scaled(p[0], p[1], p[2], 3.7, 0.4);
        }
    }
    let ns = start.elapsed().as_secs_f64() * 1e9 / (2000.0 * 4096.0);
    println!("improved smear {ns:.2} ns per call ({sum})");
}
