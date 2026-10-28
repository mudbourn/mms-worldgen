use crate::mth;
use md5::{Digest, Md5};

const FLOAT_UNIT: f32 = 5.9604645E-8;
const DOUBLE_UNIT: f32 = 1.110223E-16;
const GOLDEN_RATIO_64: i64 = -7046029254386353131;
const SILVER_RATIO_64: i64 = 7640891576956012809;

pub trait RandomSource: Sized {
    type Factory: PositionalRandomFactory<Source = Self>;

    fn next_int(&mut self) -> i32;
    fn next_int_bound(&mut self, bound: i32) -> i32;
    fn next_long(&mut self) -> i64;
    fn next_float(&mut self) -> f32;
    fn next_double(&mut self) -> f64;
    fn next_boolean(&mut self) -> bool;
    fn next_gaussian(&mut self) -> f64;
    fn consume_count(&mut self, count: i32);
    fn fork_positional(&mut self) -> Self::Factory;
}

#[allow(clippy::wrong_self_convention)]
pub trait PositionalRandomFactory {
    type Source: RandomSource;

    fn at(&self, x: i32, y: i32, z: i32) -> Self::Source;
    fn from_hash_of(&self, name: &str) -> Self::Source;
    fn from_seed(&self, seed: i64) -> Self::Source;
}

#[derive(Clone, Copy, Default)]
struct Gaussian {
    next: f64,
    have_next: bool,
}

impl Gaussian {
    fn sample<R: RandomSource>(&mut self, random: &mut R) -> f64 {
        if self.have_next {
            self.have_next = false;
            return self.next;
        }

        let (d, e, f) = loop {
            let d = 2.0 * random.next_double() - 1.0;
            let e = 2.0 * random.next_double() - 1.0;
            let f = d * d + e * e;
            if f < 1.0 && f != 0.0 {
                break (d, e, f);
            }
        };

        let g = (-2.0 * mth::log(f) / f).sqrt();
        self.next = e * g;
        self.have_next = true;
        d * g
    }
}

fn string_hash(name: &str) -> i32 {
    name.encode_utf16()
        .fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(c as i32))
}

pub struct LegacyRandomSource {
    seed: i64,
    gaussian: Gaussian,
}

impl LegacyRandomSource {
    pub fn new(seed: i64) -> Self {
        LegacyRandomSource {
            seed: (seed ^ 25214903917) & 281474976710655,
            gaussian: Gaussian::default(),
        }
    }

    fn next(&mut self, bits: i32) -> i32 {
        self.seed = self.seed.wrapping_mul(25214903917).wrapping_add(11) & 281474976710655;
        (self.seed >> (48 - bits)) as i32
    }
}

impl RandomSource for LegacyRandomSource {
    type Factory = LegacyPositionalRandomFactory;

    fn next_int(&mut self) -> i32 {
        self.next(32)
    }

    fn next_int_bound(&mut self, bound: i32) -> i32 {
        if bound & (bound - 1) == 0 {
            return ((bound as i64 * self.next(31) as i64) >> 31) as i32;
        }

        loop {
            let j = self.next(31);
            let k = j % bound;
            if j.wrapping_sub(k).wrapping_add(bound - 1) >= 0 {
                return k;
            }
        }
    }

    fn next_long(&mut self) -> i64 {
        let i = self.next(32);
        let j = self.next(32);
        ((i as i64) << 32).wrapping_add(j as i64)
    }

    fn next_float(&mut self) -> f32 {
        self.next(24) as f32 * FLOAT_UNIT
    }

    fn next_double(&mut self) -> f64 {
        let i = self.next(26);
        let j = self.next(27);
        let l = ((i as i64) << 27) + j as i64;
        l as f64 * DOUBLE_UNIT as f64
    }

    fn next_boolean(&mut self) -> bool {
        self.next(1) != 0
    }

    fn next_gaussian(&mut self) -> f64 {
        let mut gaussian = self.gaussian;
        let value = gaussian.sample(self);
        self.gaussian = gaussian;
        value
    }

    fn consume_count(&mut self, count: i32) {
        for _ in 0..count {
            self.next_int();
        }
    }

    fn fork_positional(&mut self) -> LegacyPositionalRandomFactory {
        LegacyPositionalRandomFactory {
            seed: self.next_long(),
        }
    }
}

pub struct LegacyPositionalRandomFactory {
    seed: i64,
}

impl PositionalRandomFactory for LegacyPositionalRandomFactory {
    type Source = LegacyRandomSource;

    fn at(&self, x: i32, y: i32, z: i32) -> LegacyRandomSource {
        LegacyRandomSource::new(mth::get_seed(x, y, z) ^ self.seed)
    }

    fn from_hash_of(&self, name: &str) -> LegacyRandomSource {
        LegacyRandomSource::new(string_hash(name) as i64 ^ self.seed)
    }

    fn from_seed(&self, seed: i64) -> LegacyRandomSource {
        LegacyRandomSource::new(seed)
    }
}

pub fn mix_stafford13(l: i64) -> i64 {
    let l = (l ^ ((l as u64) >> 30) as i64).wrapping_mul(-4658895280553007687);
    let l = (l ^ ((l as u64) >> 27) as i64).wrapping_mul(-7723592293110705685);
    l ^ ((l as u64) >> 31) as i64
}

#[derive(Clone, Copy)]
pub struct Seed128 {
    pub lo: i64,
    pub hi: i64,
}

impl Seed128 {
    fn xor(self, lo: i64, hi: i64) -> Seed128 {
        Seed128 {
            lo: self.lo ^ lo,
            hi: self.hi ^ hi,
        }
    }
}

pub fn upgrade_seed_to_128bit(l: i64) -> Seed128 {
    let m = l ^ SILVER_RATIO_64;
    let n = m.wrapping_add(GOLDEN_RATIO_64);
    Seed128 {
        lo: mix_stafford13(m),
        hi: mix_stafford13(n),
    }
}

pub fn seed_from_hash_of(name: &str) -> Seed128 {
    let digest = Md5::digest(name.as_bytes());
    Seed128 {
        lo: i64::from_be_bytes(digest[0..8].try_into().unwrap()),
        hi: i64::from_be_bytes(digest[8..16].try_into().unwrap()),
    }
}

pub struct XoroshiroRandomSource {
    lo: i64,
    hi: i64,
    gaussian: Gaussian,
}

impl XoroshiroRandomSource {
    pub fn new(seed: i64) -> Self {
        let seed = upgrade_seed_to_128bit(seed);
        Self::from_pair(seed.lo, seed.hi)
    }

    pub fn from_pair(lo: i64, hi: i64) -> Self {
        let (lo, hi) = if (lo | hi) == 0 {
            (GOLDEN_RATIO_64, SILVER_RATIO_64)
        } else {
            (lo, hi)
        };
        XoroshiroRandomSource {
            lo,
            hi,
            gaussian: Gaussian::default(),
        }
    }

    fn from_seed128(seed: Seed128) -> Self {
        Self::from_pair(seed.lo, seed.hi)
    }

    fn next_bits(&mut self, bits: i32) -> i64 {
        ((self.next_long() as u64) >> (64 - bits)) as i64
    }
}

impl RandomSource for XoroshiroRandomSource {
    type Factory = XoroshiroPositionalRandomFactory;

    fn next_int(&mut self) -> i32 {
        self.next_long() as i32
    }

    fn next_int_bound(&mut self, bound: i32) -> i32 {
        let bound_u = bound as u32;
        let mut l = self.next_int() as u32 as u64;
        let mut m = l * bound_u as u64;
        let mut n = m & 4294967295;
        if n < bound_u as u64 {
            let j = (!bound_u).wrapping_add(1) % bound_u;
            while n < j as u64 {
                l = self.next_int() as u32 as u64;
                m = l * bound_u as u64;
                n = m & 4294967295;
            }
        }

        (m >> 32) as i32
    }

    fn next_long(&mut self) -> i64 {
        let l = self.lo;
        let m = self.hi;
        let n = l.wrapping_add(m).rotate_left(17).wrapping_add(l);
        let m = m ^ l;
        self.lo = l.rotate_left(49) ^ m ^ (m << 21);
        self.hi = m.rotate_left(28);
        n
    }

    fn next_float(&mut self) -> f32 {
        self.next_bits(24) as f32 * FLOAT_UNIT
    }

    fn next_double(&mut self) -> f64 {
        self.next_bits(53) as f64 * DOUBLE_UNIT as f64
    }

    fn next_boolean(&mut self) -> bool {
        self.next_long() & 1 != 0
    }

    fn next_gaussian(&mut self) -> f64 {
        let mut gaussian = self.gaussian;
        let value = gaussian.sample(self);
        self.gaussian = gaussian;
        value
    }

    fn consume_count(&mut self, count: i32) {
        for _ in 0..count {
            self.next_long();
        }
    }

    fn fork_positional(&mut self) -> XoroshiroPositionalRandomFactory {
        let lo = self.next_long();
        let hi = self.next_long();
        XoroshiroPositionalRandomFactory { lo, hi }
    }
}

pub struct XoroshiroPositionalRandomFactory {
    lo: i64,
    hi: i64,
}

impl PositionalRandomFactory for XoroshiroPositionalRandomFactory {
    type Source = XoroshiroRandomSource;

    fn at(&self, x: i32, y: i32, z: i32) -> XoroshiroRandomSource {
        XoroshiroRandomSource::from_pair(mth::get_seed(x, y, z) ^ self.lo, self.hi)
    }

    fn from_hash_of(&self, name: &str) -> XoroshiroRandomSource {
        XoroshiroRandomSource::from_seed128(seed_from_hash_of(name).xor(self.lo, self.hi))
    }

    fn from_seed(&self, seed: i64) -> XoroshiroRandomSource {
        XoroshiroRandomSource::from_pair(seed ^ self.lo, seed ^ self.hi)
    }
}
