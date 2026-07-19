import { describe, expect, it } from "vitest";
import {
  convolve2d,
  outputDimension,
  padMatrix,
} from "../../src/domain/cnn-convolution";

describe("cnn convolution lab domain", () => {
  it("calculates standard CNN output dimensions", () => {
    expect(outputDimension(5, 3, 0, 1)).toBe(3);
    expect(outputDimension(5, 3, 1, 1)).toBe(5);
    expect(outputDimension(7, 3, 0, 2)).toBe(3);
    expect(outputDimension(2, 3, 0, 1)).toBe(0);
    expect(outputDimension(5, 3, -1, 1)).toBe(0);
  });

  it("pads without mutating the source matrix", () => {
    const input = [
      [1, 2],
      [3, 4],
    ];
    expect(padMatrix(input, 1)).toEqual([
      [0, 0, 0, 0],
      [0, 1, 2, 0],
      [0, 3, 4, 0],
      [0, 0, 0, 0],
    ]);
    expect(input).toEqual([
      [1, 2],
      [3, 4],
    ]);
  });

  it("returns every sliding position and its feature value", () => {
    const result = convolve2d(
      [
        [1, 2, 0],
        [0, 1, 3],
        [2, 1, 0],
      ],
      [
        [1, 0],
        [0, -1],
      ],
      { padding: 0, stride: 1 },
    );
    expect(result.output).toEqual([
      [0, -1],
      [-1, 1],
    ]);
    expect(result.positions).toHaveLength(4);
    expect(result.positions[3]).toEqual({ row: 1, col: 1, value: 1 });
  });
});
