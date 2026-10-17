const CHARACTER_WIDTH_PX = 6;

const gradient = (): { addColorStop: () => void } => ({
  addColorStop: () => undefined,
});

const fakeContext = (canvas: HTMLCanvasElement): object => {
  const known: Record<string, unknown> = {
    canvas,
    createLinearGradient: gradient,
    createRadialGradient: gradient,
    measureText: (text: string) => ({
      width: String(text).length * CHARACTER_WIDTH_PX,
    }),
  };

  return new Proxy(known, {
    get: (target, property) => {
      if (typeof property === "symbol") {
        return;
      }
      return property in target ? target[property] : () => undefined;
    },
    set: (target, property, value) => {
      if (typeof property === "string") {
        target[property] = value;
      }
      return true;
    },
  });
};

export const installCanvasStub = (): (() => void) => {
  const original = HTMLCanvasElement.prototype.getContext;

  HTMLCanvasElement.prototype.getContext = function getContext(
    this: HTMLCanvasElement
  ) {
    return fakeContext(this);
  } as typeof HTMLCanvasElement.prototype.getContext;

  return () => {
    HTMLCanvasElement.prototype.getContext = original;
  };
};
