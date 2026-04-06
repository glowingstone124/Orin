package org.qo

interface EventProcessor<T : Events> {
	fun process(args: T)
}