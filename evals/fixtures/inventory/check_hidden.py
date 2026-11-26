# 隐藏验证脚本：题目里不告诉模型，跑完后由评测框架执行。
import os, subprocess, sys
from inventory import Inventory

inv = Inventory()
inv.add("a", 5)
for bad in [(lambda: inv.remove("a", 6)), (lambda: inv.add("a", -1)), (lambda: inv.remove("a", 0))]:
    try:
        bad()
        sys.exit("expected ValueError")
    except ValueError:
        pass
assert inv.count("a") == 5, "stock must be unchanged after rejected operations"
inv.remove("a", 5)
assert inv.count("a") == 0
assert os.path.exists("test_inventory.py"), "test_inventory.py missing"
r = subprocess.run([sys.executable, "-m", "unittest", "test_inventory", "-q"], capture_output=True, text=True)
assert r.returncode == 0, r.stderr
assert r.stderr.count("ok") or "Ran" in r.stderr
print("ok")
