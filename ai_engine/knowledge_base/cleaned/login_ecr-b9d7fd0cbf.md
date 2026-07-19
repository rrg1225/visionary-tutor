---
title: "login_ecr.sh"
source_path: "d2l-en-master/d2l-en-master/ci/docker/login_ecr.sh"
source_type: code
---

# login_ecr.sh

Source: `d2l-en-master/d2l-en-master/ci/docker/login_ecr.sh`

```bash
#!/bin/bash

aws ecr get-login-password --region us-west-2 | docker login --username AWS --password-stdin 650140442593.dkr.ecr.us-west-2.amazonaws.com
```
