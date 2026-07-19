# PyTorch 训练循环

一个可审计的 PyTorch 训练循环通常在每个 batch 中执行以下步骤：先把模型和输入切换到正确的训练状态与设备，调用模型完成 forward 前向计算，再根据预测和标签计算 loss。随后调用 `optimizer.zero_grad()` 清除上一轮累积梯度，调用 `loss.backward()` 完成 backward 反向传播，最后执行 `optimizer.step()` 更新参数并记录损失、准确率等指标。

验证阶段应使用 `model.eval()` 和 `torch.no_grad()`，避免更新参数或继续构建梯度计算图。保存检查点时通常记录模型与优化器的 `state_dict`，以便恢复训练状态。

