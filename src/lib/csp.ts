export const readCspNonce = (): string | undefined => {
  const scriptWithNonce = document.querySelector<HTMLScriptElement>(
    "script[nonce], script[data-nonce]"
  );

  const nonce =
    scriptWithNonce?.nonce || scriptWithNonce?.dataset.nonce || undefined;

  return nonce === "" ? undefined : nonce;
};
