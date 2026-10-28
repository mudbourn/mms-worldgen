use crate::mth;
use crate::random::{PositionalRandomFactory, RandomSource};

const GRADIENT: [[i32; 3]; 16] = [
    [1, 1, 0],
    [-1, 1, 0],
    [1, -1, 0],
    [-1, -1, 0],
    [1, 0, 1],
    [-1, 0, 1],
    [1, 0, -1],
    [-1, 0, -1],
    [0, 1, 1],
    [0, -1, 1],
    [0, 1, -1],
    [0, -1, -1],
    [1, 1, 0],
    [0, -1, 1],
    [-1, 1, 0],
    [0, -1, -1],
];

const SHIFT_UP_EPSILON: f32 = 1.0E-7;
const NORMAL_INPUT_FACTOR: f64 = 1.0181268882175227;

pub struct ImprovedNoise {
    p: [u8; 256],
    pub xo: f64,
    pub yo: f64,
    pub zo: f64,
}

impl ImprovedNoise {
    pub fn new<R: RandomSource>(random: &mut R) -> Self {
        let xo = random.next_double() * 256.0;
        let yo = random.next_double() * 256.0;
        let zo = random.next_double() * 256.0;
        let mut p = [0u8; 256];
        for (i, value) in p.iter_mut().enumerate() {
            *value = i as u8;
        }

        for i in 0..256 {
            let j = random.next_int_bound(256 - i as i32) as usize;
            p.swap(i, i + j);
        }

        ImprovedNoise { p, xo, yo, zo }
    }

    pub fn noise(&self, d: f64, e: f64, f: f64) -> f64 {
        self.noise_scaled(d, e, f, 0.0, 0.0)
    }

    pub fn noise_scaled(&self, d: f64, e: f64, f: f64, g: f64, h: f64) -> f64 {
        let i = d + self.xo;
        let j = e + self.yo;
        let k = f + self.zo;
        let l = mth::floor(i);
        let m = mth::floor(j);
        let n = mth::floor(k);
        let o = i - l as f64;
        let p = j - m as f64;
        let q = k - n as f64;
        let s = if g != 0.0 {
            let r = if h >= 0.0 && h < p { h } else { p };
            mth::floor(r / g + SHIFT_UP_EPSILON as f64) as f64 * g
        } else {
            0.0
        };

        self.sample_and_lerp(l, m, n, o, p - s, q, p)
    }

    fn perm(&self, i: i32) -> i32 {
        self.p[(i & 0xFF) as usize] as i32
    }

    fn grad_dot(i: i32, d: f64, e: f64, f: f64) -> f64 {
        let g = &GRADIENT[(i & 15) as usize];
        g[0] as f64 * d + g[1] as f64 * e + g[2] as f64 * f
    }

    #[allow(clippy::too_many_arguments)]
    fn sample_and_lerp(&self, i: i32, j: i32, k: i32, d: f64, e: f64, f: f64, g: f64) -> f64 {
        let l = self.perm(i);
        let m = self.perm(i.wrapping_add(1));
        let n = self.perm(l.wrapping_add(j));
        let o = self.perm(l.wrapping_add(j).wrapping_add(1));
        let p = self.perm(m.wrapping_add(j));
        let q = self.perm(m.wrapping_add(j).wrapping_add(1));
        let h = Self::grad_dot(self.perm(n.wrapping_add(k)), d, e, f);
        let r = Self::grad_dot(self.perm(p.wrapping_add(k)), d - 1.0, e, f);
        let s = Self::grad_dot(self.perm(o.wrapping_add(k)), d, e - 1.0, f);
        let t = Self::grad_dot(self.perm(q.wrapping_add(k)), d - 1.0, e - 1.0, f);
        let u = Self::grad_dot(self.perm(n.wrapping_add(k).wrapping_add(1)), d, e, f - 1.0);
        let v = Self::grad_dot(
            self.perm(p.wrapping_add(k).wrapping_add(1)),
            d - 1.0,
            e,
            f - 1.0,
        );
        let w = Self::grad_dot(
            self.perm(o.wrapping_add(k).wrapping_add(1)),
            d,
            e - 1.0,
            f - 1.0,
        );
        let x = Self::grad_dot(
            self.perm(q.wrapping_add(k).wrapping_add(1)),
            d - 1.0,
            e - 1.0,
            f - 1.0,
        );
        let y = mth::smoothstep(d);
        let z = mth::smoothstep(g);
        let aa = mth::smoothstep(f);
        mth::lerp3(y, z, aa, h, r, s, t, u, v, w, x)
    }
}

pub struct PerlinNoise {
    noise_levels: Vec<Option<ImprovedNoise>>,
    amplitudes: Vec<f64>,
    lowest_freq_value_factor: f64,
    lowest_freq_input_factor: f64,
    max_value: f64,
}

impl PerlinNoise {
    pub fn create<R: RandomSource>(
        random: &mut R,
        first_octave: i32,
        amplitudes: &[f64],
    ) -> PerlinNoise {
        let factory = random.fork_positional();
        let noise_levels = amplitudes
            .iter()
            .enumerate()
            .map(|(k, amplitude)| {
                if *amplitude != 0.0 {
                    let octave = first_octave + k as i32;
                    let mut octave_random = factory.from_hash_of(&format!("octave_{octave}"));
                    Some(ImprovedNoise::new(&mut octave_random))
                } else {
                    None
                }
            })
            .collect();
        Self::finish(noise_levels, first_octave, amplitudes)
    }

    pub fn create_legacy<R: RandomSource>(
        random: &mut R,
        first_octave: i32,
        amplitudes: &[f64],
    ) -> Option<PerlinNoise> {
        let i = amplitudes.len() as i32;
        let j = -first_octave;
        let mut noise_levels: Vec<Option<ImprovedNoise>> = (0..i).map(|_| None).collect();
        let improved = ImprovedNoise::new(random);
        if j >= 0 && j < i && amplitudes[j as usize] != 0.0 {
            noise_levels[j as usize] = Some(improved);
        }

        for k in (0..j).rev() {
            if k < i && amplitudes[k as usize] != 0.0 {
                noise_levels[k as usize] = Some(ImprovedNoise::new(random));
            } else {
                random.consume_count(262);
            }
        }

        let present = noise_levels.iter().flatten().count();
        let expected = amplitudes.iter().filter(|a| **a != 0.0).count();
        if present != expected || j < i - 1 {
            return None;
        }

        Some(Self::finish(noise_levels, first_octave, amplitudes))
    }

    // Builds the legacy octave list for a contiguous octave range.
    pub fn create_legacy_for_blended<R: RandomSource>(
        random: &mut R,
        first: i32,
        last: i32,
    ) -> Option<PerlinNoise> {
        let count = last - first + 1;
        if count < 1 {
            return None;
        }

        let amplitudes = vec![1.0; count as usize];
        Self::create_legacy(random, first, &amplitudes)
    }

    fn finish(
        noise_levels: Vec<Option<ImprovedNoise>>,
        first_octave: i32,
        amplitudes: &[f64],
    ) -> PerlinNoise {
        let i = amplitudes.len() as i32;
        let j = -first_octave;
        let mut noise = PerlinNoise {
            noise_levels,
            amplitudes: amplitudes.to_vec(),
            lowest_freq_input_factor: 2.0f64.powi(-j),
            lowest_freq_value_factor: 2.0f64.powi(i - 1) / (2.0f64.powi(i) - 1.0),
            max_value: 0.0,
        };
        noise.max_value = noise.edge_value(2.0);
        noise
    }

    pub fn max_value(&self) -> f64 {
        self.max_value
    }

    pub fn get_value(&self, d: f64, e: f64, f: f64) -> f64 {
        let mut i = 0.0;
        let mut j = self.lowest_freq_input_factor;
        let mut k = self.lowest_freq_value_factor;

        for (l, level) in self.noise_levels.iter().enumerate() {
            if let Some(improved) = level {
                let m =
                    improved.noise_scaled(wrap(d * j), wrap(e * j), wrap(f * j), 0.0 * j, 0.0 * j);
                i += self.amplitudes[l] * m * k;
            }

            j *= 2.0;
            k /= 2.0;
        }

        i
    }

    pub fn max_broken_value(&self, d: f64) -> f64 {
        self.edge_value(d + 2.0)
    }

    fn edge_value(&self, d: f64) -> f64 {
        let mut e = 0.0;
        let mut f = self.lowest_freq_value_factor;

        for (i, level) in self.noise_levels.iter().enumerate() {
            if level.is_some() {
                e += self.amplitudes[i] * d * f;
            }

            f /= 2.0;
        }

        e
    }

    pub fn octave_noise(&self, i: usize) -> Option<&ImprovedNoise> {
        let index = self.noise_levels.len().checked_sub(1 + i)?;
        self.noise_levels[index].as_ref()
    }
}

pub fn wrap(d: f64) -> f64 {
    d - mth::lfloor(d / 3.3554432E7 + 0.5) as f64 * 3.3554432E7
}

pub struct NormalNoise {
    value_factor: f64,
    first: PerlinNoise,
    second: PerlinNoise,
    max_value: f64,
}

impl NormalNoise {
    pub fn create<R: RandomSource>(
        random: &mut R,
        first_octave: i32,
        amplitudes: &[f64],
    ) -> NormalNoise {
        let first = PerlinNoise::create(random, first_octave, amplitudes);
        let second = PerlinNoise::create(random, first_octave, amplitudes);
        Self::finish(first, second, amplitudes)
    }

    pub fn create_legacy_nether_biome<R: RandomSource>(
        random: &mut R,
        first_octave: i32,
        amplitudes: &[f64],
    ) -> Option<NormalNoise> {
        let first = PerlinNoise::create_legacy(random, first_octave, amplitudes)?;
        let second = PerlinNoise::create_legacy(random, first_octave, amplitudes)?;
        Some(Self::finish(first, second, amplitudes))
    }

    fn finish(first: PerlinNoise, second: PerlinNoise, amplitudes: &[f64]) -> NormalNoise {
        let mut j = i32::MAX;
        let mut k = i32::MIN;
        for (l, amplitude) in amplitudes.iter().enumerate() {
            if *amplitude != 0.0 {
                j = j.min(l as i32);
                k = k.max(l as i32);
            }
        }

        let value_factor = 0.16666666666666666 / expected_deviation(k.wrapping_sub(j));
        let max_value = (first.max_value() + second.max_value()) * value_factor;
        NormalNoise {
            value_factor,
            first,
            second,
            max_value,
        }
    }

    pub fn max_value(&self) -> f64 {
        self.max_value
    }

    pub fn get_value(&self, d: f64, e: f64, f: f64) -> f64 {
        let g = d * NORMAL_INPUT_FACTOR;
        let h = e * NORMAL_INPUT_FACTOR;
        let i = f * NORMAL_INPUT_FACTOR;
        (self.first.get_value(d, e, f) + self.second.get_value(g, h, i)) * self.value_factor
    }
}

fn expected_deviation(i: i32) -> f64 {
    0.1 * (1.0 + 1.0 / i.wrapping_add(1) as f64)
}

pub struct BlendedNoise {
    min_limit_noise: PerlinNoise,
    max_limit_noise: PerlinNoise,
    main_noise: PerlinNoise,
    xz_multiplier: f64,
    y_multiplier: f64,
    xz_factor: f64,
    y_factor: f64,
    smear_scale_multiplier: f64,
    max_value: f64,
}

impl BlendedNoise {
    pub fn new<R: RandomSource>(
        random: &mut R,
        xz_scale: f64,
        y_scale: f64,
        xz_factor: f64,
        y_factor: f64,
        smear_scale_multiplier: f64,
    ) -> Option<BlendedNoise> {
        let min_limit_noise = PerlinNoise::create_legacy_for_blended(random, -15, 0)?;
        let max_limit_noise = PerlinNoise::create_legacy_for_blended(random, -15, 0)?;
        let main_noise = PerlinNoise::create_legacy_for_blended(random, -7, 0)?;
        let y_multiplier = 684.412 * y_scale;
        let max_value = min_limit_noise.max_broken_value(y_multiplier);
        Some(BlendedNoise {
            min_limit_noise,
            max_limit_noise,
            main_noise,
            xz_multiplier: 684.412 * xz_scale,
            y_multiplier,
            xz_factor,
            y_factor,
            smear_scale_multiplier,
            max_value,
        })
    }

    pub fn max_value(&self) -> f64 {
        self.max_value
    }

    pub fn compute(&self, block_x: i32, block_y: i32, block_z: i32) -> f64 {
        let d = block_x as f64 * self.xz_multiplier;
        let e = block_y as f64 * self.y_multiplier;
        let f = block_z as f64 * self.xz_multiplier;
        let g = d / self.xz_factor;
        let h = e / self.y_factor;
        let i = f / self.xz_factor;
        let j = self.y_multiplier * self.smear_scale_multiplier;
        let k = j / self.y_factor;
        let mut l = 0.0;
        let mut m = 0.0;
        let mut n = 0.0;
        let mut o = 1.0;

        for p in 0..8 {
            if let Some(improved) = self.main_noise.octave_noise(p) {
                n += improved.noise_scaled(wrap(g * o), wrap(h * o), wrap(i * o), k * o, h * o) / o;
            }

            o /= 2.0;
        }

        let q = (n / 10.0 + 1.0) / 2.0;
        let bl2 = q >= 1.0;
        let bl3 = q <= 0.0;
        o = 1.0;

        for r in 0..16 {
            let s = wrap(d * o);
            let t = wrap(e * o);
            let u = wrap(f * o);
            let v = j * o;
            if !bl2 {
                if let Some(improved) = self.min_limit_noise.octave_noise(r) {
                    l += improved.noise_scaled(s, t, u, v, e * o) / o;
                }
            }

            if !bl3 {
                if let Some(improved) = self.max_limit_noise.octave_noise(r) {
                    m += improved.noise_scaled(s, t, u, v, e * o) / o;
                }
            }

            o /= 2.0;
        }

        mth::clamped_lerp(q, l / 512.0, m / 512.0) / 128.0
    }
}
