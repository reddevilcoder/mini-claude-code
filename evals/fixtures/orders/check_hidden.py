import pathlib, sys
sys.path.insert(0, ".")
src = "".join(p.read_text() for p in pathlib.Path(".").glob("*.py") if p.name != "check_hidden.py")
assert "calc_total" not in src, "仍有 calc_total 残留"
from models import Order
from report import summary
from billing import invoice_line
o = Order([(2.5, 2), (1, 3)])
assert o.total() == 8
assert summary([o, o]) == {"count": 2, "revenue": 16}
assert invoice_line(o) == "TOTAL 8.00"
print("ok")
