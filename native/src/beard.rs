use crate::mth;
use std::sync::OnceLock;

const KERNEL_RADIUS: i32 = 12;
const KERNEL_SIZE: usize = 24;

const NONE: i32 = 0;
const BURY: i32 = 1;
const BEARD_THIN: i32 = 2;
const BEARD_BOX: i32 = 3;
const ENCAPSULATE: i32 = 4;

pub const PIECE_INTS: usize = 8;
pub const JUNCTION_INTS: usize = 3;

#[derive(Clone, Copy, Debug, PartialEq)]
struct BoundingBox {
    min_x: i32,
    min_y: i32,
    min_z: i32,
    max_x: i32,
    max_y: i32,
    max_z: i32,
}

impl BoundingBox {
    fn from_ints(v: &[i32]) -> BoundingBox {
        BoundingBox {
            min_x: v[0],
            min_y: v[1],
            min_z: v[2],
            max_x: v[3],
            max_y: v[4],
            max_z: v[5],
        }
    }

    fn is_inside(&self, x: i32, y: i32, z: i32) -> bool {
        x >= self.min_x
            && x <= self.max_x
            && z >= self.min_z
            && z <= self.max_z
            && y >= self.min_y
            && y <= self.max_y
    }
}

#[derive(Clone, Copy, Debug)]
struct Rigid {
    bounds: BoundingBox,
    adjustment: i32,
    ground_level_delta: i32,
}

#[derive(Clone, Copy, Debug)]
struct Junction {
    source_x: i32,
    source_ground_y: i32,
    source_z: i32,
}

// Structure terrain adaptation data of one chunk, equal to vanilla's Beardifier.
#[derive(Debug, Default)]
pub struct Beardifier {
    affected: Option<BoundingBox>,
    pieces: Vec<Rigid>,
    junctions: Vec<Junction>,
}

fn kernel() -> &'static [f32] {
    static KERNEL: OnceLock<Vec<f32>> = OnceLock::new();
    KERNEL.get_or_init(|| {
        let mut values = vec![0.0f32; KERNEL_SIZE * KERNEL_SIZE * KERNEL_SIZE];
        for i in 0..KERNEL_SIZE {
            for j in 0..KERNEL_SIZE {
                for k in 0..KERNEL_SIZE {
                    values[i * KERNEL_SIZE * KERNEL_SIZE + j * KERNEL_SIZE + k] =
                        kernel_contribution(
                            j as i32 - KERNEL_RADIUS,
                            k as i32 - KERNEL_RADIUS,
                            i as i32 - KERNEL_RADIUS,
                        ) as f32;
                }
            }
        }
        values
    })
}

fn kernel_contribution(i: i32, j: i32, k: i32) -> f64 {
    let d = j as f64 + 0.5;
    let e = i as f64 * i as f64 + d * d + k as f64 * k as f64;
    std::f64::consts::E.powf(-e / 16.0)
}

fn in_kernel_range(i: i32) -> bool {
    (0..KERNEL_SIZE as i32).contains(&i)
}

fn bury_contribution(d: f64, e: f64, f: f64) -> f64 {
    let g = (d * d + e * e + f * f).sqrt();
    mth::clamped_map(g, 0.0, 6.0, 1.0, 0.0)
}

fn beard_contribution(i: i32, j: i32, k: i32, l: i32) -> f64 {
    let m = i + KERNEL_RADIUS;
    let n = j + KERNEL_RADIUS;
    let o = k + KERNEL_RADIUS;
    if in_kernel_range(m) && in_kernel_range(n) && in_kernel_range(o) {
        let d = l as f64 + 0.5;
        let e = i as f64 * i as f64 + d * d + k as f64 * k as f64;
        let f = -d * mth::fast_inv_sqrt(e / 2.0) / 2.0;
        let index = o as usize * KERNEL_SIZE * KERNEL_SIZE + m as usize * KERNEL_SIZE + n as usize;
        f * kernel()[index] as f64
    } else {
        0.0
    }
}

impl Beardifier {
    // Reads the layout from RustNative: has-box, box, counts, pieces, junctions.
    pub fn from_ints(v: &[i32]) -> Result<Beardifier, String> {
        if v.is_empty() || v[0] == 0 {
            return Ok(Beardifier::default());
        }
        if v.len() < 9 {
            return Err("beardifier header is short".to_string());
        }
        let affected = BoundingBox::from_ints(&v[1..7]);
        let pieces = v[7] as usize;
        let junctions = v[8] as usize;
        if v.len() != 9 + pieces * PIECE_INTS + junctions * JUNCTION_INTS {
            return Err("beardifier length does not match its counts".to_string());
        }
        let piece_data = &v[9..9 + pieces * PIECE_INTS];
        let junction_data = &v[9 + pieces * PIECE_INTS..];
        Ok(Beardifier {
            affected: Some(affected),
            pieces: piece_data
                .chunks_exact(PIECE_INTS)
                .map(|p| Rigid {
                    bounds: BoundingBox::from_ints(&p[0..6]),
                    adjustment: p[6],
                    ground_level_delta: p[7],
                })
                .collect(),
            junctions: junction_data
                .chunks_exact(JUNCTION_INTS)
                .map(|j| Junction {
                    source_x: j[0],
                    source_ground_y: j[1],
                    source_z: j[2],
                })
                .collect(),
        })
    }

    pub fn is_empty(&self) -> bool {
        self.affected.is_none()
    }

    // The density added to the final density at a block position.
    pub fn compute(&self, i: i32, j: i32, k: i32) -> f64 {
        let Some(affected) = &self.affected else {
            return 0.0;
        };
        if !affected.is_inside(i, j, k) {
            return 0.0;
        }

        let mut d = 0.0;
        for rigid in &self.pieces {
            let b = &rigid.bounds;
            let l = rigid.ground_level_delta;
            let m = 0.max((b.min_x - i).max(i - b.max_x));
            let n = 0.max((b.min_z - k).max(k - b.max_z));
            let o = b.min_y + l;
            let p = j - o;
            let q = match rigid.adjustment {
                BURY | BEARD_THIN => p,
                BEARD_BOX => 0.max((o - j).max(j - b.max_y)),
                ENCAPSULATE => 0.max((b.min_y - j).max(j - b.max_y)),
                _ => 0,
            };
            d += match rigid.adjustment {
                NONE => 0.0,
                BURY => bury_contribution(m as f64, q as f64 / 2.0, n as f64),
                BEARD_THIN | BEARD_BOX => beard_contribution(m, q, n, p) * 0.8,
                ENCAPSULATE => {
                    bury_contribution(m as f64 / 2.0, q as f64 / 2.0, n as f64 / 2.0) * 0.8
                }
                _ => 0.0,
            };
        }

        for junction in &self.junctions {
            let r = i - junction.source_x;
            let l = j - junction.source_ground_y;
            let m = k - junction.source_z;
            d += beard_contribution(r, l, m, l) * 0.4;
        }
        d
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header(box_ints: [i32; 6], pieces: i32, junctions: i32) -> Vec<i32> {
        let mut v = vec![1];
        v.extend(box_ints);
        v.extend([pieces, junctions]);
        v
    }

    #[test]
    fn kernel_peaks_at_the_center_and_decays() {
        let center = kernel()[12 * 576 + 12 * 24 + 12];
        let edge = kernel()[12 * 576 + 12 * 24 + 23];
        assert_eq!(center, (-0.25f64 / 16.0).exp() as f32);
        assert!(edge < center);
        assert_eq!(kernel().len(), 13824);
    }

    #[test]
    fn kernel_is_symmetric_in_x_and_z() {
        let at = |z: usize, x: usize, y: usize| kernel()[z * 576 + x * 24 + y];
        assert_eq!(at(5, 7, 9), at(7, 5, 9));
        assert_eq!(at(3, 12, 20), at(12, 3, 20));
    }

    #[test]
    fn fast_inv_sqrt_is_close_to_the_true_value() {
        let value = mth::fast_inv_sqrt(4.0);
        assert!((value - 0.5).abs() < 0.001);
        assert_eq!(value, mth::fast_inv_sqrt(4.0));
    }

    #[test]
    fn empty_beardifier_is_zero() {
        let beard = Beardifier::from_ints(&[0]).unwrap();
        assert!(beard.is_empty());
        assert_eq!(beard.compute(1, 2, 3), 0.0);
    }

    #[test]
    fn bury_piece_is_one_inside_and_zero_far_away() {
        let mut v = header([-100, 0, -100, 100, 80, 100], 1, 0);
        v.extend([0, 10, 0, 15, 20, 15, BURY, 0]);
        let beard = Beardifier::from_ints(&v).unwrap();
        assert_eq!(beard.compute(5, 10, 5), 1.0);
        assert_eq!(beard.compute(5, 40, 5), 0.0);
        let near = beard.compute(18, 10, 5);
        assert!(near > 0.0 && near < 1.0);
    }

    #[test]
    fn encapsulate_scales_by_eighty_percent() {
        let mut v = header([-100, 0, -100, 100, 80, 100], 1, 0);
        v.extend([0, 10, 0, 15, 20, 15, ENCAPSULATE, 0]);
        let beard = Beardifier::from_ints(&v).unwrap();
        assert_eq!(beard.compute(5, 15, 5), 0.8);
    }

    #[test]
    fn beard_thin_matches_the_kernel_formula() {
        let mut v = header([-100, 0, -100, 100, 80, 100], 1, 0);
        v.extend([0, 10, 0, 15, 20, 15, BEARD_THIN, 0]);
        let beard = Beardifier::from_ints(&v).unwrap();
        let p = 4;
        let d = p as f64 + 0.5;
        let e = d * d;
        let f = -d * mth::fast_inv_sqrt(e / 2.0) / 2.0;
        let k = kernel()[12 * 576 + 12 * 24 + (p + 12) as usize] as f64;
        assert_eq!(beard.compute(5, 14, 5), f * k * 0.8);
    }

    #[test]
    fn junction_contributes_inside_the_kernel_only() {
        let mut v = header([-100, 0, -100, 100, 80, 100], 0, 1);
        v.extend([0, 60, 0]);
        let beard = Beardifier::from_ints(&v).unwrap();
        assert_ne!(beard.compute(1, 60, 1), 0.0);
        assert_eq!(beard.compute(20, 60, 0), 0.0);
    }

    #[test]
    fn outside_the_affected_box_is_zero() {
        let mut v = header([0, 0, 0, 10, 10, 10], 1, 0);
        v.extend([0, 0, 0, 10, 10, 10, BURY, 0]);
        let beard = Beardifier::from_ints(&v).unwrap();
        assert_eq!(beard.compute(11, 5, 5), 0.0);
        assert!(beard.compute(5, 5, 5) > 0.0);
    }

    #[test]
    fn bad_lengths_are_rejected() {
        assert!(Beardifier::from_ints(&[1, 0, 0, 0, 1, 1, 1, 1, 0]).is_err());
    }
}
