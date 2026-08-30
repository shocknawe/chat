package com.example.chat.startup

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Slice 2 (3.1a) — pure unit coverage of [PairKeyUniqueConstraintValidator]'s
 * decision logic, independent of any database: a probe that finds the
 * constraint passes, and a failure names the constraint and states the fix
 * (so the operator's next step is in the stack trace, not in the design doc).
 *
 * The probe's SQL and the full-context fail-fast behaviour (an
 * `ApplicationRunner` failure really does abort boot) are proven against real
 * Postgres in [PairKeyUniqueConstraintBootIntegrationTest]; a mock cannot fake
 * a PostgreSQL catalog, and a unit test should not try.
 */
class PairKeyUniqueConstraintValidatorUnitTest {

    private val jdbcTemplate = mock(JdbcTemplate::class.java)

    private val validator = PairKeyUniqueConstraintValidator(jdbcTemplate)

    private val args = DefaultApplicationArguments()

    private fun stubProbe(count: Int) {
        // `doReturn` rather than `given`: the probe's generic `Class<Integer>`
        // return type makes inference on `given(...)` brittle, and this stub
        // is about the *value*, not the probe's typing.
        org.mockito.Mockito.doReturn(java.lang.Integer.valueOf(count))
            .`when`(jdbcTemplate).queryForObject(anyString(), eq(Integer::class.java), any(), any())
    }

    @Test
    fun `a present constraint passes`() {
        stubProbe(1)

        assertThatCode { validator.run(args) }.doesNotThrowAnyException()
    }

    @Test
    fun `an absent constraint fails the boot with the constraint name and the fix`() {
        stubProbe(0)

        assertThatThrownBy { validator.run(args) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(PairKeyUniqueConstraintValidator.CONSTRAINT_NAME)
            .hasMessageContaining("ALTER TABLE")
    }

    @Test
    fun `an unanticipated probe failure is still a failed boot, not a silent pass`() {
        org.mockito.Mockito.doThrow(EmptyResultDataAccessException(1))
            .`when`(jdbcTemplate).queryForObject(anyString(), eq(Integer::class.java), any(), any())

        // The probe always selects a COUNT row, so this path is defensive only;
        // it must never degrade into "assume the constraint is there".
        assertThatThrownBy { validator.run(args) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(PairKeyUniqueConstraintValidator.CONSTRAINT_NAME)
    }
}