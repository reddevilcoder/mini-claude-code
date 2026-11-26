import re


def word_counts(text):
    counts = {}
    for w in re.findall(r"[a-z']+", text.lower()):
        counts[w] = counts.get(w, 0) + 1
    return counts


def top_words(text, n):
    counts = word_counts(text)
    return sorted(counts, key=lambda w: counts[w], reverse=True)[:n]
