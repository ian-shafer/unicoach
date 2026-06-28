package ed.unicoach.admin.engine

/**
 * A per-row POST button declared by a descriptor and rendered by the engine,
 * next to the descriptor-derived Edit/Delete/Undelete actions. The descriptor
 * registers the matching route in [AdminResource.registerExtraRoutes]; the
 * engine never inspects [pathSuffix].
 *
 * @property label the button text.
 * @property pathSuffix appended to `/${slug}/${idPath}` to form the POST target.
 * @property disabledReason null => the button is enabled; non-null => the button
 *   renders disabled and the string is its hover title explaining why. Single
 *   source of truth: enabled iff this returns null.
 */
data class CustomAction<ROW>(
  val label: String,
  val pathSuffix: String,
  val disabledReason: (ROW) -> String?,
)
