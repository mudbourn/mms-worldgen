pub mod aquifer;
pub mod beard;
pub mod cell;
pub mod mth;
pub mod noise;
pub mod noisechunk;
pub mod random;
pub mod sample;
pub mod simplex;
pub mod stage;
pub mod surface;
pub mod tree;
pub mod vein;

#[cfg(test)]
mod fixture;
#[cfg(test)]
mod surface_fixture;

use jni::objects::{JClass, JDoubleArray, JIntArray, JObjectArray, JString};
use jni::sys::{jboolean, jdoubleArray, jint, jintArray, jlong, jlongArray, jstring};
use jni::JNIEnv;
use stage::Stage;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Mutex};
use surface::{Input, Surface, SurfaceSpec};
use tree::{Spec, Tree};

static TREES: Mutex<Vec<Option<Arc<Tree>>>> = Mutex::new(Vec::new());
static STAGES: Mutex<Vec<Option<Arc<Stage>>>> = Mutex::new(Vec::new());
static SURFACES: Mutex<Vec<Option<Arc<Surface>>>> = Mutex::new(Vec::new());

fn read_doubles(env: &mut JNIEnv, array: &JDoubleArray) -> Option<Vec<f64>> {
    let len = env.get_array_length(array).ok()? as usize;
    let mut buffer = vec![0.0; len];
    env.get_double_array_region(array, 0, &mut buffer).ok()?;
    Some(buffer)
}

fn read_ints(env: &mut JNIEnv, array: &JIntArray) -> Option<Vec<i32>> {
    let len = env.get_array_length(array).ok()? as usize;
    let mut buffer = vec![0; len];
    env.get_int_array_region(array, 0, &mut buffer).ok()?;
    Some(buffer)
}

fn read_strings(env: &mut JNIEnv, array: &JObjectArray) -> Option<Vec<String>> {
    let len = env.get_array_length(array).ok()?;
    let mut strings = Vec::with_capacity(len as usize);
    for i in 0..len {
        let element = env.get_object_array_element(array, i).ok()?;
        let value: String = env.get_string(&JString::from(element)).ok()?.into();
        strings.push(value);
    }
    Some(strings)
}

fn lookup(handle: jlong) -> Option<Arc<Tree>> {
    let trees = TREES.lock().ok()?;
    let slot = usize::try_from(handle.checked_sub(1)?).ok()?;
    trees.get(slot)?.clone()
}

fn lookup_surface(handle: jlong) -> Option<Arc<Surface>> {
    let surfaces = SURFACES.lock().ok()?;
    let slot = usize::try_from(handle.checked_sub(1)?).ok()?;
    surfaces.get(slot)?.clone()
}

fn int_result(env: &mut JNIEnv, values: &[i32]) -> jintArray {
    let Ok(array) = env.new_int_array(values.len() as jint) else {
        return std::ptr::null_mut();
    };
    match env.set_int_array_region(&array, 0, values) {
        Ok(()) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// Returns the crate version to RustNative.version.
#[no_mangle]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_version(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(env!("CARGO_PKG_VERSION")) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// Returns random source outputs for RustNative.randomSequence, or null on bad input.
#[no_mangle]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_randomSequence(
    env: JNIEnv,
    _class: JClass,
    kind: jint,
    seed: jlong,
    op: jint,
    arg: jint,
    count: jint,
) -> jlongArray {
    let Some(values) = sample::random_sequence(kind, seed, op, arg, count) else {
        return std::ptr::null_mut();
    };
    let Ok(array) = env.new_long_array(values.len() as jint) else {
        return std::ptr::null_mut();
    };
    match env.set_long_array_region(&array, 0, &values) {
        Ok(()) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// Returns noise samples for RustNative.sampleNoise, or null on bad input.
#[no_mangle]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_sampleNoise<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    kind: jint,
    seed: jlong,
    first_octave: jint,
    params: JDoubleArray<'local>,
    points: JDoubleArray<'local>,
) -> jdoubleArray {
    let (Some(params), Some(points)) = (
        read_doubles(&mut env, &params),
        read_doubles(&mut env, &points),
    ) else {
        return std::ptr::null_mut();
    };
    let Some(values) = sample::sample_noise(kind, seed, first_octave, &params, &points) else {
        return std::ptr::null_mut();
    };
    let Ok(array) = env.new_double_array(values.len() as jint) else {
        return std::ptr::null_mut();
    };
    match env.set_double_array_region(&array, 0, &values) {
        Ok(()) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// Builds a density tree for RustNative.createTree and returns its handle, or throws.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_createTree<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    seed: jlong,
    legacy: jboolean,
    node_ints: JIntArray<'local>,
    node_doubles: JDoubleArray<'local>,
    noise_keys: JObjectArray<'local>,
    noise_octaves: JIntArray<'local>,
    noise_amp_ends: JIntArray<'local>,
    noise_amps: JDoubleArray<'local>,
    blended: JDoubleArray<'local>,
    spline_ints: JIntArray<'local>,
    spline_consts: JDoubleArray<'local>,
    point_children: JIntArray<'local>,
    point_values: JDoubleArray<'local>,
    roots: JIntArray<'local>,
) -> jlong {
    let spec = (|| {
        Some(Spec {
            seed,
            legacy: legacy != 0,
            node_ints: read_ints(&mut env, &node_ints)?,
            node_doubles: read_doubles(&mut env, &node_doubles)?,
            noise_keys: read_strings(&mut env, &noise_keys)?,
            noise_octaves: read_ints(&mut env, &noise_octaves)?,
            noise_amp_ends: read_ints(&mut env, &noise_amp_ends)?,
            noise_amps: read_doubles(&mut env, &noise_amps)?,
            blended: read_doubles(&mut env, &blended)?,
            spline_ints: read_ints(&mut env, &spline_ints)?,
            spline_consts: read_doubles(&mut env, &spline_consts)?,
            point_children: read_ints(&mut env, &point_children)?,
            point_values: read_doubles(&mut env, &point_values)?,
            roots: read_ints(&mut env, &roots)?,
        })
    })();
    let result = match spec {
        Some(spec)
            if spec.node_ints.len() / tree::NODE_INTS * tree::NODE_DOUBLES
                == spec.node_doubles.len() =>
        {
            Tree::build(&spec)
        }
        _ => Err("tree arrays could not be read".to_string()),
    };
    match result {
        Ok(tree) => {
            let Ok(mut trees) = TREES.lock() else {
                return 0;
            };
            trees.push(Some(Arc::new(tree)));
            trees.len() as jlong
        }
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            0
        }
    }
}

// Evaluates one router field of a tree at xyz block triples for RustNative.evalTree.
#[no_mangle]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_evalTree<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    field: jint,
    points: JIntArray<'local>,
) -> jdoubleArray {
    let (Some(tree), Some(points)) = (lookup(handle), read_ints(&mut env, &points)) else {
        return std::ptr::null_mut();
    };
    if field < 0 || field as usize >= tree.root_count() || points.len() % 3 != 0 {
        return std::ptr::null_mut();
    }
    let Some(values) = tree.compute_root_points(field as usize, &points) else {
        return std::ptr::null_mut();
    };
    let Ok(array) = env.new_double_array(values.len() as jint) else {
        return std::ptr::null_mut();
    };
    match env.set_double_array_region(&array, 0, &values) {
        Ok(()) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// Releases a tree for RustNative.freeTree.
#[no_mangle]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_freeTree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let Ok(mut trees) = TREES.lock() else {
        return;
    };
    if let Some(slot) = handle
        .checked_sub(1)
        .and_then(|h| usize::try_from(h).ok())
        .and_then(|h| trees.get_mut(h))
    {
        *slot = None;
    }
}

// Builds a NOISE stage for RustNative.createStage and returns its handle, or throws.
#[no_mangle]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_createStage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    tree_handle: jlong,
    seed: jlong,
    legacy: jboolean,
    settings: JIntArray<'local>,
    flags: JIntArray<'local>,
) -> jlong {
    let result = match (
        lookup(tree_handle),
        read_ints(&mut env, &settings),
        read_ints(&mut env, &flags),
    ) {
        (Some(tree), Some(settings), Some(flags)) => {
            Stage::new(tree, seed, legacy != 0, &settings, flags)
        }
        _ => Err("stage inputs could not be read".to_string()),
    };
    match result {
        Ok(stage) => {
            let Ok(mut stages) = STAGES.lock() else {
                return 0;
            };
            stages.push(Some(Arc::new(stage)));
            stages.len() as jlong
        }
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            0
        }
    }
}

// Runs the NOISE stage of one chunk for RustNative.fillChunk, or throws.
#[no_mangle]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_fillChunk<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    chunk_x: jint,
    chunk_z: jint,
    chunk_min_y: jint,
    chunk_max_y: jint,
    beardifier: JIntArray<'local>,
) -> jintArray {
    let stage = STAGES.lock().ok().and_then(|stages| {
        let slot = usize::try_from(handle.checked_sub(1)?).ok()?;
        stages.get(slot)?.clone()
    });
    let (Some(stage), Some(beardifier)) = (stage, read_ints(&mut env, &beardifier)) else {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            "stage inputs could not be read",
        );
        return std::ptr::null_mut();
    };
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        stage.fill(chunk_x, chunk_z, chunk_min_y, chunk_max_y, &beardifier)
    }));
    let values = match outcome {
        Ok(Ok(values)) => values,
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            return std::ptr::null_mut();
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "native fill panicked");
            return std::ptr::null_mut();
        }
    };
    let Ok(array) = env.new_int_array(values.len() as jint) else {
        return std::ptr::null_mut();
    };
    match env.set_int_array_region(&array, 0, &values) {
        Ok(()) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

// Releases a stage for RustNative.freeStage.
#[no_mangle]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_freeStage(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let Ok(mut stages) = STAGES.lock() else {
        return;
    };
    if let Some(slot) = handle
        .checked_sub(1)
        .and_then(|h| usize::try_from(h).ok())
        .and_then(|h| stages.get_mut(h))
    {
        *slot = None;
    }
}

// Builds a surface system for RustNative.createSurface and returns its handle, or throws.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_createSurface<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    tree_handle: jlong,
    seed: jlong,
    legacy: jboolean,
    sea_level: jint,
    default_block: jint,
    rule_ints: JIntArray<'local>,
    cond_ints: JIntArray<'local>,
    cond_doubles: JDoubleArray<'local>,
    lists: JIntArray<'local>,
    root: jint,
    noise_keys: JObjectArray<'local>,
    noise_octaves: JIntArray<'local>,
    noise_amp_ends: JIntArray<'local>,
    noise_amps: JDoubleArray<'local>,
    gradient_names: JObjectArray<'local>,
    biome_ints: JIntArray<'local>,
) -> jlong {
    let spec = (|| {
        Some(SurfaceSpec {
            seed,
            legacy: legacy != 0,
            sea_level,
            default_block,
            rule_ints: read_ints(&mut env, &rule_ints)?,
            cond_ints: read_ints(&mut env, &cond_ints)?,
            cond_doubles: read_doubles(&mut env, &cond_doubles)?,
            lists: read_ints(&mut env, &lists)?,
            root,
            noise_keys: read_strings(&mut env, &noise_keys)?,
            noise_octaves: read_ints(&mut env, &noise_octaves)?,
            noise_amp_ends: read_ints(&mut env, &noise_amp_ends)?,
            noise_amps: read_doubles(&mut env, &noise_amps)?,
            gradient_names: read_strings(&mut env, &gradient_names)?,
            biome_ints: read_ints(&mut env, &biome_ints)?,
        })
    })();
    let built = catch_unwind(AssertUnwindSafe(|| match (lookup(tree_handle), spec) {
        (Some(tree), Some(spec)) => Surface::new(tree, &spec),
        _ => Err("surface inputs could not be read".to_string()),
    }));
    let Ok(result) = built else {
        let _ = env.throw_new("java/lang/IllegalStateException", "native surface panicked");
        return 0;
    };
    match result {
        Ok(surface) => {
            let Ok(mut surfaces) = SURFACES.lock() else {
                return 0;
            };
            surfaces.push(Some(Arc::new(surface)));
            surfaces.len() as jlong
        }
        Err(message) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            0
        }
    }
}

// Runs the surface step of one chunk for RustNative.buildSurface, or throws.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_buildSurface<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    chunk_x: jint,
    chunk_z: jint,
    min_y: jint,
    max_y: jint,
    biome_seed: jlong,
    maps: JIntArray<'local>,
    blocks: JIntArray<'local>,
    flags: JIntArray<'local>,
    biomes: JIntArray<'local>,
) -> jintArray {
    let inputs = (
        lookup_surface(handle),
        read_ints(&mut env, &maps),
        read_ints(&mut env, &blocks),
        read_ints(&mut env, &flags),
        read_ints(&mut env, &biomes),
    );
    let (Some(surface), Some(maps), Some(blocks), Some(flags), Some(biomes)) = inputs else {
        let _ = env.throw_new(
            "java/lang/IllegalArgumentException",
            "surface inputs could not be read",
        );
        return std::ptr::null_mut();
    };
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        surface.build(&Input {
            chunk_x,
            chunk_z,
            min_y,
            max_y,
            biome_seed,
            maps: &maps,
            blocks: &blocks,
            flags: &flags,
            biomes: &biomes,
        })
    }));
    match outcome {
        Ok(Ok(values)) => int_result(&mut env, &values),
        Ok(Err(message)) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", message);
            std::ptr::null_mut()
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "native surface panicked");
            std::ptr::null_mut()
        }
    }
}

// Releases a surface system for RustNative.freeSurface.
#[no_mangle]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_freeSurface(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let Ok(mut surfaces) = SURFACES.lock() else {
        return;
    };
    if let Some(slot) = handle
        .checked_sub(1)
        .and_then(|h| usize::try_from(h).ok())
        .and_then(|h| surfaces.get_mut(h))
    {
        *slot = None;
    }
}

// Zooms biomes at xyz block triples over a quart grid for RustNative.surfaceBiomes, or null.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_surfaceBiomes<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    seed: jlong,
    chunk_x: jint,
    chunk_z: jint,
    min_y: jint,
    max_y: jint,
    grid: JIntArray<'local>,
    points: JIntArray<'local>,
) -> jintArray {
    let (Some(grid), Some(points)) = (read_ints(&mut env, &grid), read_ints(&mut env, &points))
    else {
        return std::ptr::null_mut();
    };
    let quarts = (max_y - min_y + 1) / 4;
    if points.len() % 3 != 0 || quarts <= 0 || grid.len() != (36 * quarts) as usize {
        return std::ptr::null_mut();
    }
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        points
            .chunks_exact(3)
            .map(|p| {
                surface::zoom_biome(
                    &grid, seed, chunk_x, chunk_z, min_y, max_y, p[0], p[1], p[2],
                ) as i32
            })
            .collect::<Vec<i32>>()
    }));
    match outcome {
        Ok(values) => int_result(&mut env, &values),
        Err(_) => std::ptr::null_mut(),
    }
}

// Answers coldEnoughToSnow for biome, x, y, z quadruples for RustNative.surfaceCold, or null.
#[no_mangle]
pub extern "system" fn Java_info_mudbourn_mmsworldgen_rust_RustNative_surfaceCold<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    queries: JIntArray<'local>,
) -> jintArray {
    let (Some(surface), Some(queries)) = (lookup_surface(handle), read_ints(&mut env, &queries))
    else {
        return std::ptr::null_mut();
    };
    if queries.len() % 4 != 0
        || queries
            .chunks_exact(4)
            .any(|q| q[0] < 0 || q[0] as usize >= surface.biome_count())
    {
        return std::ptr::null_mut();
    }
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        queries
            .chunks_exact(4)
            .map(|q| surface.cold_enough_to_snow(q[0] as usize, q[1], q[2], q[3]) as i32)
            .collect::<Vec<i32>>()
    }));
    match outcome {
        Ok(values) => int_result(&mut env, &values),
        Err(_) => std::ptr::null_mut(),
    }
}
