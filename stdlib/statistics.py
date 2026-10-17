"""Common statistics for int/float data; no Decimal/Fraction or distribution objects."""
import math

class StatisticsError(ValueError):
    pass

def _data(data, minimum=1):
    values = list(data)
    if len(values) < minimum:
        raise StatisticsError('requires at least ' + str(minimum) + ' data points')
    for value in values:
        if not isinstance(value, (int, float, bool)):
            raise TypeError('statistics subset supports int and float data')
    return values

def _sum(values):
    # Keep integer sums exact; floating summation retains low-order partials.
    if all(isinstance(v, (int, bool)) for v in values):
        return sum(values)
    partials = []
    special = 0.0
    for value in values:
        x = float(value)
        if not math.isfinite(x):
            special += x
            continue
        remaining = []
        for y in partials:
            if abs(x) < abs(y):
                x, y = y, x
            hi = x + y
            lo = y - (hi - x)
            if lo:
                remaining.append(lo)
            x = hi
        remaining.append(x)
        partials = remaining
    if special:
        return special
    return sum(partials)

def mean(data):
    values = _data(data)
    total = _sum(values)
    if isinstance(total, int) and total % len(values) == 0:
        return total // len(values)
    return total / len(values)

def fmean(data, weights=None):
    values = _data(data)
    if weights is None:
        return float(_sum(values) / len(values))
    weights = _data(weights)
    if len(values) != len(weights):
        raise StatisticsError('data and weights must be the same length')
    total = _sum(weights)
    if total == 0:
        raise StatisticsError('sum of weights must be non-zero')
    return float(_sum([x * w for x, w in zip(values, weights)]) / total)

def median(data):
    values = sorted(_data(data))
    mid = len(values) // 2
    return values[mid] if len(values) % 2 else (values[mid - 1] + values[mid]) / 2

def median_low(data):
    values = sorted(_data(data))
    return values[(len(values) - 1) // 2]

def median_high(data):
    values = sorted(_data(data))
    return values[len(values) // 2]

def multimode(data):
    counts = {}
    best = 0
    for value in data:
        count = counts.get(value, 0) + 1
        counts[value] = count
        if count > best:
            best = count
    return [value for value in counts if counts[value] == best]

def mode(data):
    values = multimode(data)
    if not values:
        raise StatisticsError('no mode for empty data')
    return values[0]

def _variance(data, center, sample):
    values = _data(data, 2 if sample else 1)
    if center is None:
        # Shift first to avoid cancellation for data with a large common offset.
        origin = values[0]
        shifted = [x - origin for x in values]
        average = _sum(shifted) / len(values)
        diffs = [x - average for x in shifted]
    else:
        diffs = [x - center for x in values]
    return _sum([x * x for x in diffs]) / (len(values) - (1 if sample else 0))

def variance(data, xbar=None):
    return _variance(data, xbar, True)

def pvariance(data, mu=None):
    return _variance(data, mu, False)

def stdev(data, xbar=None):
    return math.sqrt(variance(data, xbar))

def pstdev(data, mu=None):
    return math.sqrt(pvariance(data, mu))

def geometric_mean(data):
    values = _data(data)
    if any(x <= 0 for x in values):
        raise StatisticsError('geometric mean requires a non-empty dataset containing positive numbers')
    return math.exp(_sum([math.log(x) for x in values]) / len(values))

def harmonic_mean(data, weights=None):
    values = _data(data)
    weights = [1] * len(values) if weights is None else _data(weights)
    if len(values) != len(weights):
        raise StatisticsError('Number of weights does not match data size')
    if any(x < 0 for x in values) or any(w < 0 for w in weights):
        raise StatisticsError('harmonic mean does not support negative values')
    total = _sum(weights)
    if total <= 0:
        raise StatisticsError('Weighted sum must be positive')
    if any(x == 0 and w > 0 for x, w in zip(values, weights)):
        return 0
    divisor = _sum([w / x for x, w in zip(values, weights) if w])
    if divisor <= 0:
        raise StatisticsError('Weighted sum must be positive')
    return total / divisor

def quantiles(data, *, n=4, method='exclusive'):
    if not isinstance(n, int):
        raise TypeError('n must be an integer')
    if n < 1:
        raise StatisticsError('n must be at least 1')
    values = sorted(_data(data, 2))
    size = len(values)
    result = []
    if method not in ('inclusive', 'exclusive'):
        raise ValueError('Unknown method: ' + method)
    for i in range(1, n):
        if method == 'inclusive':
            j, remainder = divmod(i * (size - 1), n)
            result.append((values[j] * (n - remainder) + values[j + 1] * remainder) / n)
        else:
            j = min(max(i * (size + 1) // n, 1), size - 1)
            remainder = i * (size + 1) - j * n
            result.append((values[j - 1] * (n - remainder) + values[j] * remainder) / n)
    return result
