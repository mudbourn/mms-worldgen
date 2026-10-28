use crate::noise::{BlendedNoise, ImprovedNoise, NormalNoise, PerlinNoise};
use crate::random::{
    LegacyRandomSource, PositionalRandomFactory, RandomSource, XoroshiroRandomSource,
};

// Runs one op count times on a fresh source and returns raw long results.
pub fn random_sequence(kind: i32, seed: i64, op: i32, arg: i32, count: i32) -> Option<Vec<i64>> {
    match kind {
        0 => run_ops(&mut LegacyRandomSource::new(seed), op, arg, count),
        1 => Some(run_ops(
            &mut XoroshiroRandomSource::new(seed),
            op,
            arg,
            count,
        )?),
        _ => None,
    }
}

fn run_ops<R: RandomSource>(random: &mut R, op: i32, arg: i32, count: i32) -> Option<Vec<i64>> {
    if count < 0 || (op == 1 && arg <= 0) {
        return None;
    }

    let mut out = Vec::with_capacity(count as usize);
    match op {
        0 => (0..count).for_each(|_| out.push(random.next_int() as i64)),
        1 => (0..count).for_each(|_| out.push(random.next_int_bound(arg) as i64)),
        2 => (0..count).for_each(|_| out.push(random.next_long())),
        3 => (0..count).for_each(|_| out.push(random.next_double().to_bits() as i64)),
        4 => (0..count).for_each(|_| out.push(random.next_float().to_bits() as i64)),
        5 => (0..count).for_each(|_| out.push(random.next_gaussian().to_bits() as i64)),
        6 => {
            let factory = random.fork_positional();
            for i in 0..count {
                out.push(factory.at(i, i * 3 - 5, -i).next_long());
            }
        }
        7 => {
            let factory = random.fork_positional();
            for i in 0..count {
                let name = format!("octave_{}", arg.wrapping_add(i));
                out.push(factory.from_hash_of(&name).next_long());
            }
        }
        8 => {
            random.consume_count(arg);
            (0..count).for_each(|_| out.push(random.next_long()));
        }
        _ => return None,
    }

    Some(out)
}

// Builds a noise of the given kind and samples it at each xyz triple.
pub fn sample_noise(
    kind: i32,
    seed: i64,
    first_octave: i32,
    params: &[f64],
    points: &[f64],
) -> Option<Vec<f64>> {
    if !points.len().is_multiple_of(3) {
        return None;
    }

    let sampler: Box<dyn Fn(f64, f64, f64) -> f64> = match kind {
        0 => {
            let noise = ImprovedNoise::new(&mut XoroshiroRandomSource::new(seed));
            Box::new(move |x, y, z| noise.noise(x, y, z))
        }
        1 => {
            let noise = ImprovedNoise::new(&mut LegacyRandomSource::new(seed));
            Box::new(move |x, y, z| noise.noise(x, y, z))
        }
        2 => {
            let (y_scale, y_max) = (*params.first()?, *params.get(1)?);
            let noise = ImprovedNoise::new(&mut XoroshiroRandomSource::new(seed));
            Box::new(move |x, y, z| noise.noise_scaled(x, y, z, y_scale, y_max))
        }
        3 => {
            let noise =
                PerlinNoise::create(&mut XoroshiroRandomSource::new(seed), first_octave, params);
            Box::new(move |x, y, z| noise.get_value(x, y, z))
        }
        4 => {
            let noise =
                NormalNoise::create(&mut XoroshiroRandomSource::new(seed), first_octave, params);
            Box::new(move |x, y, z| noise.get_value(x, y, z))
        }
        5 => {
            let noise = PerlinNoise::create_legacy(
                &mut LegacyRandomSource::new(seed),
                first_octave,
                params,
            )?;
            Box::new(move |x, y, z| noise.get_value(x, y, z))
        }
        6 => {
            let noise = NormalNoise::create_legacy_nether_biome(
                &mut LegacyRandomSource::new(seed),
                first_octave,
                params,
            )?;
            Box::new(move |x, y, z| noise.get_value(x, y, z))
        }
        7 => {
            let noise = BlendedNoise::new(
                &mut XoroshiroRandomSource::new(seed),
                *params.first()?,
                *params.get(1)?,
                *params.get(2)?,
                *params.get(3)?,
                *params.get(4)?,
            )?;
            Box::new(move |x, y, z| noise.compute(x as i32, y as i32, z as i32))
        }
        8 => {
            let noise =
                PerlinNoise::create(&mut LegacyRandomSource::new(seed), first_octave, params);
            Box::new(move |x, y, z| noise.get_value(x, y, z))
        }
        _ => return None,
    };

    Some(
        points
            .chunks_exact(3)
            .map(|p| sampler(p[0], p[1], p[2]))
            .collect(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const POINTS: [f64; 9] = [
        0.5,
        64.0,
        -0.5,
        30000000.0,
        64.0,
        -30000000.0,
        1.0,
        64.0,
        -1.0,
    ];
    const SHAPE: [f64; 4] = [1.0, 0.0, 2.0, 0.5];
    const BLENDED: [f64; 5] = [0.25, 0.125, 80.0, 160.0, 8.0];

    fn bits(values: Vec<f64>) -> Vec<i64> {
        values.iter().map(|v| v.to_bits() as i64).collect()
    }

    #[test]
    fn legacy_random_matches_vanilla() {
        let run = |op, arg| random_sequence(0, 1, op, arg, 3).unwrap();
        assert_eq!(run(0, 0), [-1155869325, 431529176, 1761283695]);
        assert_eq!(run(1, 7), [4, 4, 1]);
        assert_eq!(
            run(2, 0),
            [
                -4964420948893066024,
                7564655870752979346,
                3831662765844904176
            ]
        );
        assert_eq!(
            run(3, 0),
            [
                4604758385039885542,
                4601058979077218200,
                4596651736145909532
            ]
        );
        assert_eq!(run(4, 0), [1060838101, 1036895456, 1053947420]);
        assert_eq!(
            run(5, 0),
            [
                4609711554963350721,
                -4619718795384920767,
                -4615778764195122810
            ]
        );
        assert_eq!(
            run(6, 0),
            [
                -3641151594931469673,
                -6495087190510371497,
                3058860172523957894
            ]
        );
        assert_eq!(
            run(8, 262),
            [
                -7243389174711685803,
                5818523204758407422,
                451431109683129954
            ]
        );
    }

    #[test]
    fn xoroshiro_random_matches_vanilla() {
        let run = |op, arg| random_sequence(1, 1, op, arg, 3).unwrap();
        assert_eq!(run(0, 0), [1734564350, 836234122, 825264738]);
        assert_eq!(run(1, 7), [2, 1, 1]);
        assert_eq!(
            run(2, 0),
            [
                -1033667707219518978,
                6451672561743293322,
                -1821890263888393630
            ]
        );
        assert_eq!(
            run(3, 0),
            [
                4606677698239851627,
                4599972081403983354,
                4606292823944603153
            ]
        );
        assert_eq!(run(4, 0), [1064413100, 1051922916, 1063696216]);
        assert_eq!(
            run(5, 0),
            [
                4602348428318625399,
                -4628330909472231807,
                -4626918804019704064
            ]
        );
        assert_eq!(
            run(6, 0),
            [
                3392018836757151057,
                703230061059207852,
                -2263206045275087533
            ]
        );
        assert_eq!(
            run(7, -10),
            [
                6081012949404039927,
                6814426398750499101,
                -2351988814010156354
            ]
        );
    }

    #[test]
    fn improved_noise_matches_vanilla() {
        let xoro = sample_noise(0, 1, 0, &[], &POINTS).unwrap();
        assert_eq!(
            bits(xoro),
            [
                -4621503017217519755,
                -4636321646344051488,
                4585960340197720528
            ]
        );
        let legacy = sample_noise(1, 1, 0, &[], &POINTS).unwrap();
        assert_eq!(
            bits(legacy),
            [
                4579750112692034656,
                -4628404477506112923,
                -4629096541305713865
            ]
        );
    }

    #[test]
    fn perlin_and_normal_noise_match_vanilla() {
        let perlin = sample_noise(3, 1, -7, &SHAPE, &POINTS).unwrap();
        assert_eq!(
            bits(perlin),
            [
                4591996144732583664,
                4592224475788834818,
                4591353315096679706
            ]
        );
        let normal = sample_noise(4, 1, -7, &SHAPE, &POINTS).unwrap();
        assert_eq!(
            bits(normal),
            [
                4578797743930100341,
                4600618794309050024,
                4574921612367518677
            ]
        );
        let nether = sample_noise(6, 1, -7, &SHAPE, &POINTS).unwrap();
        assert_eq!(
            bits(nether),
            [
                -4628544788927325638,
                -4633452122256592345,
                -4629243741400847002
            ]
        );
    }

    #[test]
    fn blended_noise_matches_vanilla() {
        let blended = sample_noise(7, 1, 0, &BLENDED, &POINTS).unwrap();
        assert_eq!(
            bits(blended),
            [
                -4632108792457451398,
                4593586134844009084,
                -4631929676771945241
            ]
        );
    }

    #[test]
    fn bad_input_returns_none() {
        assert!(random_sequence(2, 1, 0, 0, 1).is_none());
        assert!(random_sequence(0, 1, 1, 0, 1).is_none());
        assert!(sample_noise(0, 1, 0, &[], &[0.0, 1.0]).is_none());
        assert!(sample_noise(5, 1, 0, &[1.0, 1.0], &POINTS).is_none());
    }
}
