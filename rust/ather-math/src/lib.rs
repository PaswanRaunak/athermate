use jni::{objects::{JClass, JDoubleArray, JLongArray}, sys::{jdouble, jdoubleArray, jintArray, jlong}, JNIEnv};

// Returns indices into the original measurements, never interpolated battery values.
fn history_indices(times: &[i64], soc: &[f64], start: i64, end: i64) -> Vec<i32> {
    let mut indices: Vec<usize> = times.iter().enumerate().filter_map(|(i, &t)| {
        let value = *soc.get(i)?;
        (t > 0 && t >= start && t <= end && value.is_finite() && (0.0..=100.0).contains(&value)).then_some(i)
    }).collect();
    indices.sort_by_key(|&i| times[i]);
    indices.dedup_by_key(|i| times[*i]);
    indices.into_iter().map(|i| i as i32).collect()
}

#[no_mangle]
pub extern "system" fn Java_io_ather_pro_data_computation_RustTelemetryMath_historyIndices(
    mut env: JNIEnv, _class: JClass, timestamps: JLongArray, values: JDoubleArray, start: jlong, end: jlong,
) -> jintArray {
    let result = (|| -> jni::errors::Result<jintArray> {
        let mut times = vec![0; env.get_array_length(&timestamps)? as usize];
        let mut soc = vec![0.0; env.get_array_length(&values)? as usize];
        env.get_long_array_region(&timestamps, 0, &mut times)?;
        env.get_double_array_region(&values, 0, &mut soc)?;
        let indices = history_indices(&times, &soc, start, end);
        let array = env.new_int_array(indices.len() as i32)?;
        env.set_int_array_region(&array, 0, &indices)?;
        Ok(array.into_raw())
    })();
    match result { Ok(array) => array, Err(_) => {
        let _ = env.throw_new("java/lang/IllegalStateException", "Native battery history processing failed");
        std::ptr::null_mut()
    }}
}

#[no_mangle]
pub extern "system" fn Java_io_ather_pro_data_computation_RustTelemetryMath_scaleRange(
    _env: JNIEnv, _class: JClass, current: jdouble, mode: jdouble, active: jdouble,
) -> jdouble {
    let value = current * mode / active;
    if value.is_finite() && value >= 0.0 { value } else { f64::NAN }
}

#[no_mangle]
pub extern "system" fn Java_io_ather_pro_data_computation_RustTelemetryMath_chargeEstimate(
    mut env: JNIEnv, _class: JClass, soc: jdouble, target: jdouble, capacity: jdouble,
    tariff: jdouble, range: jdouble, eta80: jdouble, eta100: jdouble,
) -> jdoubleArray {
    let target = target.clamp(0.0, 100.0);
    let remaining = (target - soc).max(0.0);
    let energy = capacity * remaining / 100_000.0;
    let eta = if remaining == 0.0 { 0.0 }
        else if target <= 80.0 && soc < 80.0 && eta80.is_finite() && eta80 >= 0.0 { eta80 * remaining / (80.0 - soc) }
        else if soc < 100.0 && eta100.is_finite() && eta100 >= 0.0 { eta100 * remaining / (100.0 - soc) }
        else { f64::NAN };
    let projected = if soc >= 5.0 && range.is_finite() && range >= 0.0 { range * target / soc } else { f64::NAN };
    let result = (|| -> jni::errors::Result<jdoubleArray> {
        let array = env.new_double_array(5)?;
        env.set_double_array_region(&array, 0, &[remaining, energy, energy * tariff, projected, eta])?;
        Ok(array.into_raw())
    })();
    match result { Ok(array) => array, Err(_) => {
        let _ = env.throw_new("java/lang/IllegalStateException", "Native range processing failed");
        std::ptr::null_mut()
    }}
}

// Minutes from the battery measurement, not from the HTTP/WebSocket receive time.
#[no_mangle]
pub extern "system" fn Java_io_ather_pro_data_computation_RustTelemetryMath_chargeTimeEstimate(
    mut env: JNIEnv, _class: JClass, soc: jdouble, target: jdouble, capacity: jdouble,
    power: jdouble, eta80: jdouble, eta100: jdouble, observed_rate: jdouble,
) -> jdoubleArray {
    let remaining = (target.clamp(0.0, 100.0) - soc).max(0.0);
    let (minutes, basis) = if remaining == 0.0 { (0.0, 1.0) }
        else if target <= 80.0 && soc < 80.0 && eta80.is_finite() && eta80 > 0.0 {
            (eta80 * remaining / (80.0 - soc), 1.0)
        } else if soc < 100.0 && eta100.is_finite() && eta100 > 0.0 {
            (eta100 * remaining / (100.0 - soc), 1.0)
        } else if observed_rate.is_finite() && (0.01..=10.0).contains(&observed_rate) {
            (remaining / observed_rate, 2.0)
        } else { (capacity * remaining / 100.0 / power * 60.0, 3.0) };
    let result = (|| -> jni::errors::Result<jdoubleArray> {
        let array = env.new_double_array(2)?;
        env.set_double_array_region(&array, 0, &[minutes, basis])?;
        Ok(array.into_raw())
    })();
    match result { Ok(array) => array, Err(_) => {
        let _ = env.throw_new("java/lang/IllegalStateException", "Native charge-time estimation failed");
        std::ptr::null_mut()
    }}
}
