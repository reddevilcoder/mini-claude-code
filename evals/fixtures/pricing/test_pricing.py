import unittest
from pricing import paginate, apply_discount


class PricingTest(unittest.TestCase):
    def test_first_page(self):
        self.assertEqual(paginate(list(range(10)), 1, 3), [0, 1, 2])

    def test_last_page(self):
        self.assertEqual(paginate(list(range(10)), 4, 3), [9])

    def test_discount(self):
        self.assertEqual(apply_discount(200, 15), 170.0)


if __name__ == "__main__":
    unittest.main()
