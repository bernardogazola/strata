export const readCspNonce = (): string | undefined => {
  const styleNonce =
    document.querySelector<HTMLStyleElement>("style[nonce]")?.nonce;
  if (styleNonce) {
    return styleNonce;
  }

  const scriptWithNonce = document.querySelector<HTMLScriptElement>(
    "script[nonce], script[data-nonce]"
  );

  return scriptWithNonce?.nonce || scriptWithNonce?.dataset.nonce || undefined;
};
