package org.qo

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object ActionsDiscover {
	private val actions: ConcurrentHashMap<Class<out Events>, CopyOnWriteArrayList<EventProcessor<out Events>>> =
		ConcurrentHashMap()

	fun <T : Events> register(eventType: Class<T>, processor: EventProcessor<T>) {
		actions.computeIfAbsent(eventType) { CopyOnWriteArrayList() }.add(processor)
	}

	@Suppress("UNCHECKED_CAST")
	fun <T : Events> dispatch(event: T): Int {
		val processors = actions[event.javaClass] ?: return 0
		processors.forEach { processor ->
			(processor as EventProcessor<T>).process(event)
		}
		return processors.size
	}

	fun clear() {
		actions.clear()
	}
}
