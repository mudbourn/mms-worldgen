const DIMS: usize = 7;
const FIELDS_PER_NODE: usize = DIMS * 2;
const NO_TREE: i32 = -1;

// Climate.Parameter.distance: how far a value lies outside a span.
fn parameter_distance(min: i64, max: i64, value: i64) -> i64 {
    let above = value.wrapping_sub(max);
    let below = min.wrapping_sub(value);
    if above > 0 {
        above
    } else {
        below.max(0)
    }
}

// Climate.RTree nodes of one or more trees, laid out exactly as vanilla built them.
pub struct Forest {
    params: Vec<i64>,
    child_start: Vec<u32>,
    child_count: Vec<u32>,
    value: Vec<i32>,
    children: Vec<u32>,
    roots: Vec<i32>,
}

impl Forest {
    // Builds a forest from node spans, child ranges, leaf values, child lists and tree roots.
    pub fn new(
        params: &[i64],
        meta: &[i32],
        children: &[i32],
        roots: &[i32],
    ) -> Result<Forest, String> {
        if meta.len() % 3 != 0 {
            return Err("tree meta is not triples".to_string());
        }
        let nodes = meta.len() / 3;
        if params.len() != nodes * FIELDS_PER_NODE {
            return Err("tree spans do not match the node count".to_string());
        }
        let mut child_start = Vec::with_capacity(nodes);
        let mut child_count = Vec::with_capacity(nodes);
        let mut value = Vec::with_capacity(nodes);
        for (id, m) in meta.chunks_exact(3).enumerate() {
            let (start, count) = (m[0], m[1]);
            if start < 0 || count < 0 || (start as usize + count as usize) > children.len() {
                return Err("tree child range is out of bounds".to_string());
            }
            if (count == 0) != (m[2] >= 0) {
                return Err("tree node is neither a leaf nor a subtree".to_string());
            }
            for child in &children[start as usize..(start + count) as usize] {
                if *child <= id as i32 || *child as usize >= nodes {
                    return Err("tree child does not follow its parent".to_string());
                }
            }
            child_start.push(start as u32);
            child_count.push(count as u32);
            value.push(m[2]);
        }
        if roots
            .iter()
            .any(|r| *r != NO_TREE && (*r < 0 || *r as usize >= nodes))
        {
            return Err("tree root is out of bounds".to_string());
        }
        Ok(Forest {
            params: params.to_vec(),
            child_start,
            child_count,
            value,
            children: children.iter().map(|c| *c as u32).collect(),
            roots: roots.to_vec(),
        })
    }

    pub fn tree_count(&self) -> usize {
        self.roots.len()
    }

    pub fn has_tree(&self, tree: usize) -> bool {
        self.roots.get(tree).is_some_and(|r| *r != NO_TREE)
    }

    // Node.distance: sum of squared parameter distances over the seven spans.
    fn distance(&self, node: usize, target: &[i64; DIMS]) -> i64 {
        let spans = &self.params[node * FIELDS_PER_NODE..(node + 1) * FIELDS_PER_NODE];
        let mut total = 0i64;
        for i in 0..DIMS {
            let d = parameter_distance(spans[i * 2], spans[i * 2 + 1], target[i]);
            total = total.wrapping_add(d.wrapping_mul(d));
        }
        total
    }

    fn search_node(&self, node: usize, target: &[i64; DIMS], best: Option<usize>) -> usize {
        if self.child_count[node] == 0 {
            return node;
        }
        let mut least = best.map_or(i64::MAX, |b| self.distance(b, target));
        let mut leaf = best;
        let start = self.child_start[node] as usize;
        for child in &self.children[start..start + self.child_count[node] as usize] {
            let child = *child as usize;
            let near = self.distance(child, target);
            if least > near {
                let found = self.search_node(child, target, leaf);
                let found_distance = if child == found {
                    near
                } else {
                    self.distance(found, target)
                };
                if least > found_distance {
                    least = found_distance;
                    leaf = Some(found);
                }
            }
        }
        leaf.unwrap_or(node)
    }

    // RTree.search: the leaf value nearest the target, warm started from the last result.
    pub fn search(
        &self,
        tree: usize,
        target: &[i64; DIMS],
        last: &mut Option<usize>,
    ) -> Option<i32> {
        let root = *self.roots.get(tree)?;
        if root == NO_TREE {
            return None;
        }
        let leaf = self.search_node(root as usize, target, *last);
        *last = Some(leaf);
        Some(self.value[leaf])
    }

    // The smallest distance from the target to any leaf of a tree with the given value.
    pub fn best_distance(&self, tree: usize, value: i32, target: &[i64; DIMS]) -> Option<i64> {
        let root = *self.roots.get(tree)?;
        if root == NO_TREE {
            return None;
        }
        let mut best: Option<i64> = None;
        let mut stack = vec![root as usize];
        while let Some(node) = stack.pop() {
            if self.child_count[node] == 0 {
                if self.value[node] == value {
                    let d = self.distance(node, target);
                    best = Some(best.map_or(d, |b| b.min(d)));
                }
                continue;
            }
            let start = self.child_start[node] as usize;
            let count = self.child_count[node] as usize;
            stack.extend(
                self.children[start..start + count]
                    .iter()
                    .map(|c| *c as usize),
            );
        }
        best
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn leaf(spans: [(i64, i64); DIMS]) -> Vec<i64> {
        spans.iter().flat_map(|(a, b)| [*a, *b]).collect()
    }

    fn point(v: [i64; DIMS]) -> Vec<i64> {
        leaf([
            (v[0], v[0]),
            (v[1], v[1]),
            (v[2], v[2]),
            (v[3], v[3]),
            (v[4], v[4]),
            (v[5], v[5]),
            (v[6], v[6]),
        ])
    }

    fn flat(points: &[[i64; DIMS]]) -> Forest {
        let mut params = leaf([(-100000, 100000); DIMS]);
        let mut meta = vec![0, points.len() as i32, -1];
        let children: Vec<i32> = (1..=points.len() as i32).collect();
        for (i, p) in points.iter().enumerate() {
            params.extend(point(*p));
            meta.extend([0, 0, i as i32]);
        }
        Forest::new(&params, &meta, &children, &[0]).unwrap()
    }

    #[test]
    fn parameter_distance_is_zero_inside_and_linear_outside() {
        assert_eq!(parameter_distance(-5, 5, 0), 0);
        assert_eq!(parameter_distance(-5, 5, 5), 0);
        assert_eq!(parameter_distance(-5, 5, 9), 4);
        assert_eq!(parameter_distance(-5, 5, -9), 4);
    }

    #[test]
    fn search_finds_the_nearest_leaf() {
        let forest = flat(&[
            [0, 0, 0, 0, 0, 0, 0],
            [100, 0, 0, 0, 0, 0, 0],
            [0, 100, 0, 0, 0, 0, 0],
        ]);
        let mut last = None;
        assert_eq!(
            forest.search(0, &[90, 5, 0, 0, 0, 0, 0], &mut last),
            Some(1)
        );
        assert_eq!(
            forest.search(0, &[3, 80, 0, 0, 0, 0, 0], &mut last),
            Some(2)
        );
        assert_eq!(
            forest.search(0, &[-4, -4, 0, 0, 0, 0, 0], &mut last),
            Some(0)
        );
    }

    #[test]
    fn ties_keep_the_first_leaf_found_and_the_warm_start() {
        let forest = flat(&[[-10, 0, 0, 0, 0, 0, 0], [10, 0, 0, 0, 0, 0, 0]]);
        let target = [0, 0, 0, 0, 0, 0, 0];
        let mut last = None;
        assert_eq!(forest.search(0, &target, &mut last), Some(0));
        let mut warm = Some(2);
        assert_eq!(forest.search(0, &target, &mut warm), Some(1));
    }

    #[test]
    fn nested_search_matches_brute_force() {
        let mut state = 0x2545F4914F6CDD1Du64;
        let mut next = |bound: i64| {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            (state % bound as u64) as i64
        };
        let points: Vec<[i64; DIMS]> = (0..40)
            .map(|_| {
                let mut p = [0i64; DIMS];
                for v in p.iter_mut().take(6) {
                    *v = next(20000) - 10000;
                }
                p
            })
            .collect();
        let mut params = leaf([(-100000, 100000); DIMS]);
        let mut meta = vec![0, 4, -1];
        let mut children = vec![1, 2, 3, 4];
        let mut group_spans = Vec::new();
        for g in 0..4 {
            let members: Vec<&[i64; DIMS]> = points.iter().skip(g).step_by(4).collect();
            let mut spans = [(i64::MAX, i64::MIN); DIMS];
            for p in &members {
                for i in 0..DIMS {
                    spans[i] = (spans[i].0.min(p[i]), spans[i].1.max(p[i]));
                }
            }
            group_spans.push(spans);
        }
        for spans in &group_spans {
            params.extend(leaf(*spans));
            meta.extend([0, 0, -1]);
        }
        let mut leaf_children: Vec<Vec<i32>> = vec![Vec::new(); 4];
        for (i, p) in points.iter().enumerate() {
            let id = (meta.len() / 3) as i32;
            params.extend(point(*p));
            meta.extend([0, 0, i as i32]);
            leaf_children[i % 4].push(id);
        }
        for (g, ids) in leaf_children.iter().enumerate() {
            meta[(g + 1) * 3] = children.len() as i32;
            meta[(g + 1) * 3 + 1] = ids.len() as i32;
            children.extend(ids);
        }
        let forest = Forest::new(&params, &meta, &children, &[0]).unwrap();
        for _ in 0..500 {
            let mut target = [0i64; DIMS];
            for v in target.iter_mut().take(6) {
                *v = next(24000) - 12000;
            }
            let mut last = None;
            let found = forest.search(0, &target, &mut last).unwrap();
            let best = points
                .iter()
                .map(|p| {
                    (0..DIMS)
                        .map(|i| (p[i] - target[i]) * (p[i] - target[i]))
                        .sum::<i64>()
                })
                .min()
                .unwrap();
            let got: i64 = (0..DIMS)
                .map(|i| {
                    let p = points[found as usize][i];
                    (p - target[i]) * (p - target[i])
                })
                .sum();
            assert_eq!(got, best);
            assert_eq!(forest.best_distance(0, found, &target), Some(best));
        }
    }

    #[test]
    fn malformed_forests_are_rejected() {
        let params = point([0; DIMS]);
        assert!(Forest::new(&params, &[0, 0, 5], &[], &[3]).is_err());
        assert!(Forest::new(&params, &[0, 1, -1], &[0], &[0]).is_err());
        assert!(Forest::new(&params, &[0, 0, -1], &[], &[0]).is_err());
    }
}
