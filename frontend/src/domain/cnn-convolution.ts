export type Matrix = number[][];

export interface ConvolutionOptions {
  padding: number;
  stride: number;
}

export interface ConvolutionResult {
  paddedInput: Matrix;
  output: Matrix;
  positions: Array<{ row: number; col: number; value: number }>;
  outputSize: number;
}

export function outputDimension(
  inputSize: number,
  kernelSize: number,
  padding: number,
  stride: number,
) {
  if (inputSize <= 0 || kernelSize <= 0 || padding < 0 || stride <= 0) return 0;
  const numerator = inputSize - kernelSize + 2 * padding;
  return numerator < 0 ? 0 : Math.floor(numerator / stride) + 1;
}

export function padMatrix(input: Matrix, padding: number): Matrix {
  if (!input.length || padding <= 0) return input.map((row) => [...row]);
  const width = input[0].length + padding * 2;
  const zeroRow = () => Array.from({ length: width }, () => 0);
  return [
    ...Array.from({ length: padding }, zeroRow),
    ...input.map((row) => [
      ...Array.from({ length: padding }, () => 0),
      ...row,
      ...Array.from({ length: padding }, () => 0),
    ]),
    ...Array.from({ length: padding }, zeroRow),
  ];
}

export function convolve2d(
  input: Matrix,
  kernel: Matrix,
  options: ConvolutionOptions,
): ConvolutionResult {
  const { padding, stride } = options;
  const outputSize = outputDimension(
    input.length,
    kernel.length,
    padding,
    stride,
  );
  const paddedInput = padMatrix(input, padding);
  const output: Matrix = Array.from({ length: outputSize }, () =>
    Array.from({ length: outputSize }, () => 0),
  );
  const positions: ConvolutionResult["positions"] = [];

  for (let outRow = 0; outRow < outputSize; outRow += 1) {
    for (let outCol = 0; outCol < outputSize; outCol += 1) {
      const row = outRow * stride;
      const col = outCol * stride;
      let value = 0;
      for (let kr = 0; kr < kernel.length; kr += 1) {
        for (let kc = 0; kc < kernel[kr].length; kc += 1) {
          value += paddedInput[row + kr][col + kc] * kernel[kr][kc];
        }
      }
      output[outRow][outCol] = value;
      positions.push({ row, col, value });
    }
  }

  return { paddedInput, output, positions, outputSize };
}
