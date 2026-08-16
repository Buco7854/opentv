/**
 * How much to enlarge the whole interface on a big screen.
 *
 * The layout is expressed in pixels rather than relative units, so it renders at one
 * physical size whatever it is shown on. That suits a phone held at arm's length and
 * reads small on a car or television screen, which is wide but viewed from further
 * away. Scaling the interface keeps every proportion the design intends instead of
 * enlarging text inside controls that stay put.
 *
 * The decision is made here, in script, rather than in a width media query, because
 * the scale is applied with `zoom`: were the query to be re-evaluated against the
 * scaled viewport it could stop matching, un-apply, match again, and flicker. Screen
 * width does not move when we zoom, so reading it once cannot oscillate.
 */
export type UiScale = 'default' | 'large' | 'largest';

/** Where the steps sit. A laptop stays at its designed size; a cockpit screen grows. */
export const LARGE_SCREEN_PX = 1280;
export const LARGEST_SCREEN_PX = 1700;

export function uiScaleFor(screenWidth: number): UiScale {
  if (screenWidth >= LARGEST_SCREEN_PX) return 'largest';
  if (screenWidth >= LARGE_SCREEN_PX) return 'large';
  return 'default';
}

/**
 * Read the screen once and record the answer for the stylesheet.
 *
 * `screen.width` rather than `innerWidth`: a window occupying part of a large display
 * is still being watched from across a room, and a browser that reports the screen in
 * device pixels only errs towards the size we already wanted.
 */
export function applyUiScale(
  root: HTMLElement = document.documentElement,
  screenWidth: number = window.screen?.width ?? window.innerWidth,
): UiScale {
  const scale = uiScaleFor(screenWidth);
  root.dataset.uiScale = scale;
  return scale;
}
