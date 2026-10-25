export const pinIndexes = (
  indexes: readonly number[],
  pinned: readonly (number | null)[]
): number[] => {
  const extra = pinned.filter(
    (index): index is number => index !== null && !indexes.includes(index)
  );
  if (extra.length === 0) {
    return [...indexes];
  }
  return [...new Set([...indexes, ...extra])].sort((a, b) => a - b);
};
