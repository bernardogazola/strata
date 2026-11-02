const SEED = 31n;
const MASK_64 = 2n ** 64n - 1n;
const MIN_NORMAL = 2 ** -1022;
const EXPONENT_FIELD = 0x7f_f0_00_00_00_00_00_00n;
const BITS_SAMPLE = 1500;
const DECIMAL_SAMPLE = 1500;
const SHORT_SAMPLE = 1000;
const PLAIN_SAMPLE = 1000;
const POWER_OF_TEN_MIN = -30;
const POWER_OF_TEN_MAX = 30;
const DECIMAL_EXPONENT_MIN = -40;
const DECIMAL_EXPONENT_SPAN = 81;
const SHORT_EXPONENT_MIN = -35;
const SHORT_EXPONENT_SPAN = 66;
const SHORT_DIGITS_MAX = 9999;
const PLAIN_LOG_MIN = -6;
const PLAIN_LOG_SPAN = 27;
const PLAIN_MAX_EXCLUSIVE = 1e21;
const PAGERANK_TELEPORT = 0.15;
const PAGERANK_SMALL_N = 100;
const PAGERANK_STEPS_PER_DECADE = 16;
const PAGERANK_FIRST_STEP = 32;
const PAGERANK_LAST_STEP = 112;
const BITS_HEX_DIGITS = 16;
const MANTISSA_SHIFT = 11n;
const MANTISSA_SCALE = 2 ** 53;

// biome-ignore-start lint/suspicious/noBitwiseOperators: o PRNG e o campo de expoente são definidos por bits
const splitmix64 = (seed) => {
  let state = seed;
  return () => {
    state = (state + 0x9e_37_79_b9_7f_4a_7c_15n) & MASK_64;
    let z = state;
    z = ((z ^ (z >> 30n)) * 0xbf_58_47_6d_1c_e4_e5_b9n) & MASK_64;
    z = ((z ^ (z >> 27n)) * 0x94_d0_49_bb_13_31_11_ebn) & MASK_64;
    return z ^ (z >> 31n);
  };
};

const nextBits = splitmix64(SEED);
const nextUnit = () => Number(nextBits() >> MANTISSA_SHIFT) / MANTISSA_SCALE;
const nextSign = () => (nextBits() & 1n ? -1 : 1);
const isFiniteBits = (bits) => (bits & EXPONENT_FIELD) !== EXPONENT_FIELD;
// biome-ignore-end lint/suspicious/noBitwiseOperators: fim do PRNG e do campo de expoente
const nextInt = (span) => Math.floor(nextUnit() * span);

const view = new DataView(new ArrayBuffer(8));
const fromBits = (bits) => {
  view.setBigUint64(0, bits);
  return view.getFloat64(0);
};
const toBits = (value) => {
  view.setFloat64(0, value);
  return view.getBigUint64(0);
};

const representable = (value) =>
  Number.isFinite(value) && (value === 0 || Math.abs(value) >= MIN_NORMAL);

const rows = new Map();
const add = (source, value) => {
  if (!representable(value)) {
    return false;
  }
  const hex = toBits(value).toString(16).padStart(BITS_HEX_DIGITS, "0");
  if (!rows.has(hex)) {
    rows.set(hex, `${hex}\t${JSON.stringify(value)}\t${source}`);
  }
  return true;
};
const addSigned = (source, value) => {
  add(source, value);
  add(source, -value);
};

const FIXED = [
  0,
  1e-6,
  1e-7,
  1.5e-7,
  MIN_NORMAL,
  1e21,
  9.999_999_999_999_999e20,
  1e22,
  1.5e300,
  2.5e-10,
  1 / 3,
  0.1 + 0.2,
  7.37e-6,
  3.81e-6,
  1e-10,
  8.6e-13,
  PAGERANK_TELEPORT / 150_001,
  0.000_001_234_5,
  Number.parseFloat("1.2345678901234567e-7"),
  0.1,
  0.5,
  0.0041,
  1,
  2,
  4.35,
  88,
  100,
  1e20,
  123_456_789_012_345_680_000,
  Number.parseFloat("123456789012345678901234"),
  Number.EPSILON,
  Number.MAX_SAFE_INTEGER,
  2 ** 53,
  2 ** 53 + 2,
  Number.MAX_VALUE,
];
for (const value of FIXED) {
  if (!representable(value)) {
    throw new Error(`valor fixo fora do contrato: ${value}`);
  }
  addSigned("fixed", value);
}

for (let k = POWER_OF_TEN_MIN; k <= POWER_OF_TEN_MAX; k += 1) {
  const power = Number.parseFloat(`1e${k}`);
  addSigned("pow10", power);
  addSigned("ulp", fromBits(toBits(power) + 1n));
  addSigned("ulp", fromBits(toBits(power) - 1n));
}
add("ulp", fromBits(toBits(MIN_NORMAL) + 1n));
add("ulp", fromBits(toBits(Number.MAX_VALUE) - 1n));

for (let n = 1; n <= PAGERANK_SMALL_N; n += 1) {
  add("pagerank", PAGERANK_TELEPORT / n);
  add("pagerank", 1 / n);
}
for (let step = PAGERANK_FIRST_STEP; step <= PAGERANK_LAST_STEP; step += 1) {
  const n = Math.floor(10 ** (step / PAGERANK_STEPS_PER_DECADE));
  add("pagerank", PAGERANK_TELEPORT / n);
  add("pagerank", 1 / n);
}

let bitsKept = 0;
while (bitsKept < BITS_SAMPLE) {
  const bits = nextBits();
  if (isFiniteBits(bits) && add("bits", fromBits(bits))) {
    bitsKept += 1;
  }
}

for (let i = 0; i < DECIMAL_SAMPLE; i += 1) {
  const exponent = DECIMAL_EXPONENT_MIN + nextInt(DECIMAL_EXPONENT_SPAN);
  add("decimal", nextSign() * (1 + 9 * nextUnit()) * 10 ** exponent);
}

for (let i = 0; i < SHORT_SAMPLE; i += 1) {
  const digits = 1 + nextInt(SHORT_DIGITS_MAX);
  const exponent = SHORT_EXPONENT_MIN + nextInt(SHORT_EXPONENT_SPAN);
  add("short", nextSign() * Number.parseFloat(`${digits}e${exponent}`));
}

for (let i = 0; i < PLAIN_SAMPLE; i += 1) {
  const value = 10 ** (PLAIN_LOG_MIN + PLAIN_LOG_SPAN * nextUnit());
  if (value < PLAIN_MAX_EXCLUSIVE) {
    add("plain", value);
  }
}

const header = [
  "# js-number-forms.tsv: o JSON.stringify de cada double, o oráculo do CanonicalNumbersTest.",
  "# Nunca editado à mão nem tirado do analisador. Regenerar, da raiz do repositório:",
  "#   node scripts/js-number-oracle.mjs > analyzer/src/test/resources/protocol/js-number-forms.tsv",
  `# Gerado com node ${process.version}, semente ${SEED}, ${rows.size} linhas.`,
  "# Colunas: bits IEEE 754 em 16 hex minúsculos, TAB, JSON.stringify(valor), TAB, origem.",
  "# Fora do contrato: NaN, Infinity e os subnormais, 5e-324 entre eles.",
];
process.stdout.write(`${[...header, ...rows.values()].join("\n")}\n`);
