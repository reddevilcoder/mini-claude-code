from textstats import top_words

# 同频词需按字母序稳定排序；n 超出词数时返回全部；n<=0 返回空列表。
assert top_words("b a c a b", 2) == ["a", "b"], top_words("b a c a b", 2)
assert top_words("zeta alpha beta", 3) == ["alpha", "beta", "zeta"]
assert top_words("x y", 10) == ["x", "y"]
assert top_words("x y", 0) == []
print("ok")
