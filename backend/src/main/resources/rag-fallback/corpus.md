# CNN 卷积与特征图尺寸

卷积神经网络中，输出特征图尺寸可由公式计算：
输出尺寸 = (输入尺寸 - 卷积核尺寸 + 2 × padding) / stride + 1

当 padding=1、kernel=3、stride=1 时，特征图尺寸通常与输入相同。

---
# Padding 与 Stride

padding 在输入边缘补零，避免特征图过快缩小；stride 控制滑窗步长。
stride 越大，特征图越小，感受野增长更快。

---
# 反向传播与卷积

卷积层反向传播需要保存前向中间结果；PyTorch 中可用 `conv2d` 输出 shape 调试：
`torch.nn.Conv2d(in_channels, out_channels, kernel_size, stride, padding)`。

---
# 长难句：卷积输出尺寸综合表述

当卷积神经网络采用三乘三卷积核、步长 stride 等于一、并在输入边缘进行 padding 补零时，
输出特征图尺寸与输入尺寸之间的关系可由公式
输出尺寸 = (输入尺寸 - 卷积核尺寸 + 2 × padding) / stride + 1
精确描述；在 padding=1、kernel=3、stride=1 的常见设置下，特征图高宽通常与输入保持一致。
