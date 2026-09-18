package xyz.satr.davprovider.ui

import xyz.satr.davprovider.core.DavAccount
import xyz.satr.davprovider.core.DavCollection

/**
 * What one attempt at discovery produced. [completed] distinguishes "the server has no Collections"
 * from "nothing was learned about the server": only the former may mark a stored Collection
 * unavailable, so a failed enumeration can never look like a disappearance.
 */
internal data class DiscoveryOutcome(
    val completed: Boolean,
    val collections: List<DavCollection>,
    val notes: List<String>,
)

/**
 * The seam §8's discovery plugs into.
 *
 * The progressive walk — `.well-known/carddav` and `.well-known/caldav`, then `current-user-principal`,
 * then the home set, then enumerating it, each step reported with its own outcome — belongs to the
 * sync slice, which owns the DAV layer and the §5 classifier it reuses. Until it is connected here,
 * this reports that it enumerated nothing and sends no request.
 */
internal object CollectionDiscovery {

    fun discover(davAccount: DavAccount): DiscoveryOutcome =
        DiscoveryOutcome(completed = false, collections = emptyList(), notes = emptyList())

    /**
     * Folds a completed enumeration into the stored selection.
     *
     * Every stored Collection survives — one the server no longer lists becomes unavailable rather
     * than deleted, because a listing that failed and a Collection that vanished are not the same
     * observation. A newly discovered Collection arrives **unselected**, so a calendar that appeared
     * on the server does not silently start writing into the phone.
     *
     * Only ever called with [DiscoveryOutcome.completed].
     */
    fun merge(existing: List<DavCollection>, discovered: List<DavCollection>): List<DavCollection> {
        val fresh = discovered.associateBy { it.id }
        val kept = existing.map { prior ->
            fresh[prior.id]?.copy(id = prior.id, selected = prior.selected) ?: prior.copy(available = false)
        }
        val added = discovered
            .filter { entry -> existing.none { it.id == entry.id } }
            .map { it.copy(selected = false) }
        return kept + added
    }
}
