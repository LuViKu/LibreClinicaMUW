import type { ComputedRef, InjectionKey } from 'vue'

/**
 * Provide/inject key for the CRF authoring canvas's per-field error map.
 *
 * <p>{@link CrfAuthoringCanvasView} provides a {@code ComputedRef<Record<string,
 * string>>} keyed on item OID; descendants (SectionCanvas, PropertiesRail,
 * future item editors) inject and check {@code errors.value[item.oid]} to
 * decide whether to render a red border + inline error message.
 *
 * <p>Inject default is an empty computed so consumers don't need to
 * null-check before reading. Safe to inject from any descendant.
 */
export const CrfAuthoringErrorsKey: InjectionKey<ComputedRef<Record<string, string>>> = Symbol(
  'CrfAuthoringErrors',
)
