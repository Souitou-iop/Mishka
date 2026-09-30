package top.yukonga.mishka.domain.model

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 覆写执行顺序解析。
 *
 * 顺序直接决定 transform 叠加结果（YAML 与 JS 逐个 push 到同一 transform 清单），
 * 早期 bug 是「用户拖动排序后实际执行顺序仍是选择顺序」，这里固定住优先级规则。
 */
class OverrideSelectionTest {

    private fun sub(selected: List<String>, sortPreference: List<String>) = Subscription(
        id = "s1",
        overrideIds = selected.toPersistentList(),
        overrideSortPreference = sortPreference.toPersistentList(),
    )

    @Test
    fun `unsorted selection falls back to selection order`() {
        assertEquals(
            listOf("a", "b", "c"),
            sub(selected = listOf("a", "b", "c"), sortPreference = emptyList()).orderedOverrideIds,
        )
    }

    @Test
    fun `sort preference reorders selected items`() {
        assertEquals(
            listOf("c", "a", "b"),
            sub(selected = listOf("a", "b", "c"), sortPreference = listOf("c")).orderedOverrideIds,
        )
    }

    @Test
    fun `sort preference wins over selection order when it covers all`() {
        assertEquals(
            listOf("c", "b", "a"),
            sub(selected = listOf("a", "b", "c"), sortPreference = listOf("c", "b", "a")).orderedOverrideIds,
        )
    }

    @Test
    fun `unselected entries in sort preference are dropped`() {
        // 排序偏好里残留的已取消选择项不能被执行
        assertEquals(
            listOf("a"),
            sub(selected = listOf("a"), sortPreference = listOf("ghost", "a")).orderedOverrideIds,
        )
    }

    @Test
    fun `unranked selected items keep selection order after ranked ones`() {
        assertEquals(
            listOf("b", "a", "c"),
            sub(selected = listOf("a", "b", "c"), sortPreference = listOf("b")).orderedOverrideIds,
        )
    }

    @Test
    fun `duplicates in either list collapse to one execution`() {
        assertEquals(
            listOf("a", "b"),
            sub(selected = listOf("a", "a", "b"), sortPreference = listOf("a", "a")).orderedOverrideIds,
        )
    }

    @Test
    fun `empty selection yields empty order`() {
        assertEquals(
            emptyList<String>(),
            sub(selected = emptyList(), sortPreference = listOf("a", "b")).orderedOverrideIds,
        )
    }

    @Test
    fun `order is stable regardless of sort preference length`() {
        val selected = listOf("x", "y", "z")
        assertEquals(
            listOf("z", "x", "y"),
            sub(selected = selected, sortPreference = listOf("z")).orderedOverrideIds,
        )
        assertEquals(
            listOf("z", "y", "x"),
            sub(selected = selected, sortPreference = listOf("z", "y", "x")).orderedOverrideIds,
        )
    }
}
