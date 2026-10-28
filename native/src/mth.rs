#![allow(clippy::excessive_precision)]

// Floors a double to an int the way Mth.floor does.
pub fn floor(d: f64) -> i32 {
    let i = d as i32;
    if d < i as f64 {
        i - 1
    } else {
        i
    }
}

// Floors a double to a long the way Mth.lfloor does.
pub fn lfloor(d: f64) -> i64 {
    let l = d as i64;
    if d < l as f64 {
        l - 1
    } else {
        l
    }
}

pub fn lerp(d: f64, e: f64, f: f64) -> f64 {
    e + d * (f - e)
}

pub fn lerp2(d: f64, e: f64, f: f64, g: f64, h: f64, i: f64) -> f64 {
    lerp(e, lerp(d, f, g), lerp(d, h, i))
}

#[allow(clippy::too_many_arguments)]
pub fn lerp3(
    d: f64,
    e: f64,
    f: f64,
    g: f64,
    h: f64,
    i: f64,
    j: f64,
    k: f64,
    l: f64,
    m: f64,
    n: f64,
) -> f64 {
    lerp(f, lerp2(d, e, g, h, i, j), lerp2(d, e, k, l, m, n))
}

pub fn inverse_lerp(d: f64, e: f64, f: f64) -> f64 {
    (d - e) / (f - e)
}

pub fn clamped_map(d: f64, e: f64, f: f64, g: f64, h: f64) -> f64 {
    clamped_lerp(inverse_lerp(d, e, f), g, h)
}

pub fn map(d: f64, e: f64, f: f64, g: f64, h: f64) -> f64 {
    lerp(inverse_lerp(d, e, f), g, h)
}

// Clamps like Mth.clamp for doubles.
pub fn clamp(d: f64, e: f64, f: f64) -> f64 {
    if d < e {
        e
    } else {
        java_min(d, f)
    }
}

// Mth.quantize: floors d / i and scales back by i.
pub fn quantize(d: f64, i: i32) -> i32 {
    floor(d / i as f64).wrapping_mul(i)
}

// Mth.fastInvSqrt on doubles.
pub fn fast_inv_sqrt(d: f64) -> f64 {
    let e = 0.5 * d;
    let l = d.to_bits() as i64;
    let l = 6910469410427058090i64.wrapping_sub(l >> 1);
    let d = f64::from_bits(l as u64);
    d * (1.5 - e * d * d)
}

// Math.min for doubles with Java NaN and signed zero handling.
pub fn java_min(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        a
    } else if b.is_nan() {
        b
    } else if a == 0.0 && b == 0.0 {
        if a.is_sign_negative() {
            a
        } else {
            b
        }
    } else if a < b {
        a
    } else {
        b
    }
}

// Math.max for doubles with Java NaN and signed zero handling.
pub fn java_max(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        a
    } else if b.is_nan() {
        b
    } else if a == 0.0 && b == 0.0 {
        if a.is_sign_negative() {
            b
        } else {
            a
        }
    } else if a > b {
        a
    } else {
        b
    }
}

pub fn smoothstep(d: f64) -> f64 {
    d * d * d * (d * (d * 6.0 - 15.0) + 10.0)
}

pub fn clamped_lerp(d: f64, e: f64, f: f64) -> f64 {
    if d < 0.0 {
        e
    } else if d > 1.0 {
        f
    } else {
        lerp(d, e, f)
    }
}

// Mixes a block position into a seed like Mth.getSeed.
pub fn get_seed(i: i32, j: i32, k: i32) -> i64 {
    let l = (i.wrapping_mul(3129871) as i64) ^ (k as i64).wrapping_mul(116129781) ^ (j as i64);
    let l = l
        .wrapping_mul(l)
        .wrapping_mul(42317861)
        .wrapping_add(l.wrapping_mul(11));
    l >> 16
}

const LN2_HI: f64 = 6.93147180369123816490e-01;
const LN2_LO: f64 = 1.90821492927058770002e-10;
const TWO54: f64 = 1.80143985094819840000e+16;
const LG1: f64 = 6.666666666666735130e-01;
const LG2: f64 = 3.999999999940941908e-01;
const LG3: f64 = 2.857142874366239149e-01;
const LG4: f64 = 2.222219843214978396e-01;
const LG5: f64 = 1.818357216161805012e-01;
const LG6: f64 = 1.531383769920937332e-01;
const LG7: f64 = 1.479819860511658591e-01;

// Natural logarithm ported from fdlibm e_log.c, the algorithm behind StrictMath.log.
pub fn log(x: f64) -> f64 {
    let mut x = x;
    let mut bits = x.to_bits();
    let mut hx = (bits >> 32) as i32;
    let lx = bits as u32;
    let mut k: i32 = 0;

    if hx < 0x00100000 {
        if ((hx & 0x7fffffff) as u32 | lx) == 0 {
            return f64::NEG_INFINITY;
        }
        if hx < 0 {
            return f64::NAN;
        }
        k -= 54;
        x *= TWO54;
        bits = x.to_bits();
        hx = (bits >> 32) as i32;
    }

    if hx >= 0x7ff00000 {
        return x + x;
    }

    k += (hx >> 20) - 1023;
    hx &= 0x000fffff;
    let i = (hx + 0x95f64) & 0x100000;
    x = f64::from_bits((((hx | (i ^ 0x3ff00000)) as u64) << 32) | (x.to_bits() & 0xffffffff));
    k += i >> 20;
    let f = x - 1.0;
    let dk = k as f64;

    if (0x000fffff & (2 + hx)) < 3 {
        if f == 0.0 {
            return if k == 0 {
                0.0
            } else {
                dk * LN2_HI + dk * LN2_LO
            };
        }
        let r = f * f * (0.5 - 0.33333333333333333 * f);
        return if k == 0 {
            f - r
        } else {
            dk * LN2_HI - ((r - dk * LN2_LO) - f)
        };
    }

    let s = f / (2.0 + f);
    let z = s * s;
    let w = z * z;
    let t1 = w * (LG2 + w * (LG4 + w * LG6));
    let t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)));
    let r = t2 + t1;
    let positive = ((hx - 0x6147a) | (0x6b851 - hx)) > 0;

    if positive {
        let hfsq = 0.5 * f * f;
        if k == 0 {
            f - (hfsq - s * (hfsq + r))
        } else {
            dk * LN2_HI - ((hfsq - (s * (hfsq + r) + dk * LN2_LO)) - f)
        }
    } else if k == 0 {
        f - s * (f - r)
    } else {
        dk * LN2_HI - ((s * (f - r) - dk * LN2_LO) - f)
    }
}
