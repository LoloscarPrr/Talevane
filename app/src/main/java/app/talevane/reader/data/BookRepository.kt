package app.talevane.reader.data

import app.talevane.reader.application.library.BookLibrary

/**
 * Compatibility alias while presentation call sites are migrated incrementally.
 * New code should depend on BookLibrary; RoomBookRepository is the concrete data implementation.
 */
typealias BookRepository = BookLibrary
