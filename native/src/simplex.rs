use crate::mth;
use crate::random::{LegacyRandomSource, RandomSource};

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

// SimplexNoise reduced to the two dimensional lookup the biome temperature uses.
pub struct SimplexNoise {
    p: [i32; 512],
}

impl SimplexNoise {
    pub fn new<R: RandomSource>(random: &mut R) -> SimplexNoise {
        for _ in 0..3 {
            random.next_double();
        }
        let mut p = [0i32; 512];
        for (i, slot) in p.iter_mut().enumerate().take(256) {
            *slot = i as i32;
        }
        for i in 0..256usize {
            let j = random.next_int_bound(256 - i as i32) as usize;
            p.swap(i, j + i);
        }
        SimplexNoise { p }
    }

    fn perm(&self, i: i32) -> i32 {
        self.p[(i & 0xFF) as usize]
    }

    fn corner(i: i32, d: f64, e: f64, f: f64, g: f64) -> f64 {
        let mut h = g - d * d - e * e - f * f;
        if h < 0.0 {
            0.0
        } else {
            h *= h;
            let gradient = GRADIENT[i as usize];
            h * h * (gradient[0] as f64 * d + gradient[1] as f64 * e + gradient[2] as f64 * f)
        }
    }

    pub fn value(&self, d: f64, e: f64) -> f64 {
        let sqrt3 = 3.0f64.sqrt();
        let f2 = 0.5 * (sqrt3 - 1.0);
        let g2 = (3.0 - sqrt3) / 6.0;
        let f = (d + e) * f2;
        let i = mth::floor(d + f);
        let j = mth::floor(e + f);
        let g = (i + j) as f64 * g2;
        let h = i as f64 - g;
        let k = j as f64 - g;
        let l = d - h;
        let m = e - k;
        let (n, o) = if l > m { (1, 0) } else { (0, 1) };
        let p = l - n as f64 + g2;
        let q = m - o as f64 + g2;
        let r = l - 1.0 + 2.0 * g2;
        let s = m - 1.0 + 2.0 * g2;
        let t = i & 0xFF;
        let u = j & 0xFF;
        let v = self.perm(t + self.perm(u)) % 12;
        let w = self.perm(t + n + self.perm(u + o)) % 12;
        let x = self.perm(t + 1 + self.perm(u + 1)) % 12;
        let y = Self::corner(v, l, m, 0.0, 0.5);
        let z = Self::corner(w, p, q, 0.0, 0.5);
        let aa = Self::corner(x, r, s, 0.0, 0.5);
        70.0 * (y + z + aa)
    }
}

// PerlinSimplexNoise over the contiguous octaves ending at zero, seeded from a legacy source.
pub struct PerlinSimplexNoise {
    levels: Vec<SimplexNoise>,
    input_factor: f64,
    value_factor: f64,
}

impl PerlinSimplexNoise {
    pub fn new(seed: i64, octave_count: usize) -> PerlinSimplexNoise {
        let mut random = LegacyRandomSource::new(seed);
        let levels = (0..octave_count)
            .map(|_| SimplexNoise::new(&mut random))
            .collect();
        PerlinSimplexNoise {
            levels,
            input_factor: 1.0,
            value_factor: 1.0 / (2f64.powi(octave_count as i32) - 1.0),
        }
    }

    pub fn value(&self, d: f64, e: f64) -> f64 {
        let mut sum = 0.0;
        let mut g = self.input_factor;
        let mut h = self.value_factor;
        for level in &self.levels {
            sum += level.value(d * g + 0.0, e * g + 0.0) * h;
            g /= 2.0;
            h *= 2.0;
        }
        sum
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn values_are_bounded_and_deterministic() {
        let noise = PerlinSimplexNoise::new(1234, 1);
        let again = PerlinSimplexNoise::new(1234, 1);
        for i in 0..200 {
            let x = i as f64 * 0.37 - 20.0;
            let z = i as f64 * 0.91 + 3.0;
            let value = noise.value(x, z);
            assert!(value.abs() <= 1.0 + 1e-9);
            assert_eq!(value.to_bits(), again.value(x, z).to_bits());
        }
    }
}
