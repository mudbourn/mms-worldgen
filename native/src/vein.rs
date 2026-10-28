use crate::mth;
use crate::noisechunk::{Eval, ROOT_VEIN_GAP, ROOT_VEIN_RIDGED, ROOT_VEIN_TOGGLE};
use crate::stage::Stage;
use crate::tree::Tree;

const VEININESS_THRESHOLD: f32 = 0.4;
const VEIN_SOLIDNESS: f32 = 0.7;
const MIN_RICHNESS: f32 = 0.1;
const MAX_RICHNESS: f32 = 0.3;
const MAX_RICHNESS_THRESHOLD: f32 = 0.6;
const CHANCE_OF_RAW_ORE_BLOCK: f32 = 0.02;
const SKIP_ORE_IF_GAP_NOISE_IS_BELOW: f32 = -0.3;

// Ore vein substance at a block position: None leaves the block to the default.
pub fn substance(
    stage: &Stage,
    tree: &Tree,
    eval: &mut Eval,
    x: i32,
    y: i32,
    z: i32,
) -> Option<usize> {
    let d = eval.chunk(tree, ROOT_VEIN_TOGGLE, x, y, z);
    let copper = d > 0.0;
    let (min_y, max_y) = if copper { (0, 50) } else { (-60, -8) };
    let e = d.abs();
    let j = max_y - y;
    let k = y - min_y;
    if k < 0 || j < 0 {
        return None;
    }

    let l = j.min(k);
    let f = mth::clamped_map(l as f64, 0.0, 20.0, -0.2, 0.0);
    if e + f < VEININESS_THRESHOLD as f64 {
        return None;
    }

    let mut random = stage.ore_random.at(x, y, z);
    if random.next_float() > VEIN_SOLIDNESS {
        return None;
    }
    if eval.chunk(tree, ROOT_VEIN_RIDGED, x, y, z) >= 0.0 {
        return None;
    }

    let g = mth::clamped_map(
        e,
        VEININESS_THRESHOLD as f64,
        MAX_RICHNESS_THRESHOLD as f64,
        MIN_RICHNESS as f64,
        MAX_RICHNESS as f64,
    );
    if (random.next_float() as f64) < g
        && eval.chunk(tree, ROOT_VEIN_GAP, x, y, z) > SKIP_ORE_IF_GAP_NOISE_IS_BELOW as f64
    {
        let raw = random.next_float() < CHANCE_OF_RAW_ORE_BLOCK;
        let roles = &stage.roles;
        Some(match (copper, raw) {
            (true, true) => roles.raw_copper,
            (true, false) => roles.copper_ore,
            (false, true) => roles.raw_iron,
            (false, false) => roles.iron_ore,
        })
    } else if copper {
        Some(stage.roles.granite)
    } else {
        Some(stage.roles.tuff)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn thresholds_widen_from_float_constants() {
        assert_eq!(VEININESS_THRESHOLD as f64, 0.4000000059604645);
        assert_eq!(MAX_RICHNESS as f64, 0.30000001192092896);
        assert_eq!(SKIP_ORE_IF_GAP_NOISE_IS_BELOW as f64, -0.30000001192092896);
    }

    #[test]
    fn richness_ramps_between_the_thresholds() {
        let low = mth::clamped_map(
            0.4f32 as f64,
            0.4f32 as f64,
            0.6f32 as f64,
            0.1f32 as f64,
            0.3f32 as f64,
        );
        let high = mth::clamped_map(
            0.9,
            0.4f32 as f64,
            0.6f32 as f64,
            0.1f32 as f64,
            0.3f32 as f64,
        );
        assert_eq!(low, 0.1f32 as f64);
        assert_eq!(high, 0.3f32 as f64);
    }

    #[test]
    fn edge_roundoff_reaches_minus_point_two_at_the_vein_edge() {
        assert_eq!(mth::clamped_map(0.0, 0.0, 20.0, -0.2, 0.0), -0.2);
        assert_eq!(mth::clamped_map(20.0, 0.0, 20.0, -0.2, 0.0), 0.0);
        assert!((mth::clamped_map(10.0, 0.0, 20.0, -0.2, 0.0) + 0.1).abs() < 1e-15);
    }
}
