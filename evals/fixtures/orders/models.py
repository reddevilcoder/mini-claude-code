class Order:
    def __init__(self, items):
        self.items = items  # list of (price, qty)

    def calc_total(self):
        return sum(p * q for p, q in self.items)
