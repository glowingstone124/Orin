package org.qo

interface EventProcessor<T> {
	fun process(args: T)
}