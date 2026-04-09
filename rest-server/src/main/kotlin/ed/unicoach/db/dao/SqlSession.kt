package ed.unicoach.db.dao

import java.sql.PreparedStatement

/**
 * Functional boundary enforcing zero side-effects to database transaction state.
 * By physically not passing java.sql.Connection, we mathematically guarantee
 * that DAO methods cannot perform .commit() or .rollback().
 */
interface SqlSession {
    fun prepareStatement(sql: String): PreparedStatement
}
