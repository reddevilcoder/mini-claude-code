def paginate(items, page, page_size):
    """Return the items on the given 1-based page."""
    start = page * page_size
    return items[start:start + page_size]


def apply_discount(price, percent):
    """Apply a percentage discount, rounded to 2 decimals."""
    return round(price * (1 - percent / 100), 2)
