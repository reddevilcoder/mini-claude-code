from models import Order


def summary(orders):
    return {"count": len(orders), "revenue": sum(o.calc_total() for o in orders)}
