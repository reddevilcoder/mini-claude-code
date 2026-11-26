from models import Order


def invoice_line(order: Order) -> str:
    return f"TOTAL {order.calc_total():.2f}"
