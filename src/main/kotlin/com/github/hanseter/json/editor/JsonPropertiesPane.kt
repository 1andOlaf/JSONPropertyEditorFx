package com.github.hanseter.json.editor

import com.github.hanseter.json.editor.actions.ActionsContainer
import com.github.hanseter.json.editor.actions.EditorAction
import com.github.hanseter.json.editor.controls.ArrayControl
import com.github.hanseter.json.editor.controls.ObjectControl
import com.github.hanseter.json.editor.controls.TypeControl
import com.github.hanseter.json.editor.extensions.*
import com.github.hanseter.json.editor.util.EditorContext
import com.github.hanseter.json.editor.util.PropertyGrouping
import com.github.hanseter.json.editor.util.RootBindableType
import com.github.hanseter.json.editor.util.ViewOptions
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
import javafx.scene.control.Control
import javafx.scene.control.Label
import org.controlsfx.validation.Severity
import org.controlsfx.validation.ValidationMessage
import org.controlsfx.validation.decoration.GraphicValidationDecoration
import org.everit.json.schema.Schema
import org.everit.json.schema.ValidationException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONPointer

class JsonPropertiesPane(
        title: String,
        data: JSONObject,
        schema: Schema,
        private val refProvider: IdReferenceProposalProvider,
        private val actions: List<EditorAction>,
        private val validators: List<com.github.hanseter.json.editor.validators.Validator>,
        private val viewOptions: ViewOptions,
        private val changeListener: (JSONObject) -> JSONObject
) {
    val treeItem: FilterableTreeItem<TreeItemData> = FilterableTreeItem(SectionRootTreeItemData(title))
    private val schema = SimpleEffectiveSchema(null, schema, title)
    private var objectControl: TypeControl? = null
    private var controlItem: FilterableTreeItem<TreeItemData>? = null
    private val contentHandler = ContentHandler(data)
    val valid = SimpleBooleanProperty(true)

    init {
        treeItem.isExpanded = false
        treeItem.expandedProperty().addListener { _, _, new ->
            if (new) {
                contentHandler.handleExpansion()
            }
        }
    }

    private fun initObjectControl() {
        val objectControl = ControlFactory.convert(schema, EditorContext(refProvider, ::updateTreeAfterChildChange))
        controlItem = wrapControlInTreeItem(objectControl)
        if (controlItem!!.isLeaf) treeItem.add(controlItem!!)
        else Bindings.bindContent(treeItem.list, controlItem!!.list)
        this.objectControl = objectControl
    }

    private fun wrapControlInTreeItem(control: TypeControl): FilterableTreeItem<TreeItemData> {
        val actions = ActionsContainer(control, actions) { e, action, control ->
            val ret = action.apply(contentHandler.data, control.model, e)
            if (ret != null) {
                val newData = changeListener(ret)
                fillData(newData)
            }
        }

        val item: FilterableTreeItem<TreeItemData> =
                FilterableTreeItem(ControlTreeItemData(control, actions, validators.filter { it.selector.matches(control.model) }, viewOptions))
        if (control is ObjectControl) {
            addObjectControlChildren(item, control)
        } else {
            item.list.setAll(control.childControls.map { wrapControlInTreeItem(it) })
        }
        return item
    }

    fun fillData(data: JSONObject) {
        contentHandler.updateData(data)
    }

    private fun fillSheet(data: JSONObject) {
        val type = RootBindableType(data)
        objectControl?.bindTo(type)
        fillTree(data)
        type.registerListener {
            val newData = changeListener(type.value!!)
            fillData(newData)
        }
    }

    private fun fillTree(data: JSONObject) {
        controlItem?.also { item ->
            objectControl?.also {
                updateTree(item, it)
            }
            updateTreeUiElements(item, data)
        }
    }

    private fun updateTreeAfterChildChange(control: TypeControl) {
        val item = findInTree(treeItem, control) ?: return
        val iterNewChilds = control.childControls.listIterator()
        val iterOldChilds = item.list.listIterator()
        while (iterNewChilds.hasNext() && iterOldChilds.hasNext()) {
            val newChild = iterNewChilds.next()
            val oldChild = iterOldChilds.next()
            if (newChild::class != oldChild::class) {
                iterOldChilds.set(wrapControlInTreeItem(newChild))
            }
        }
        iterNewChilds.forEachRemaining {
            iterOldChilds.add(wrapControlInTreeItem(it))
        }
        while (iterOldChilds.hasNext()) {
            iterOldChilds.remove()
        }
    }

    private fun findInTree(item: FilterableTreeItem<TreeItemData>, control: TypeControl): FilterableTreeItem<TreeItemData>? =
            item.list.find { (it.value as? ControlTreeItemData)?.typeControl == control }
                    ?: item.list.asSequence().mapNotNull { findInTree(it, control) }.firstOrNull()

    private fun updateTree(item: FilterableTreeItem<TreeItemData>, control: TypeControl) {
        when (control) {
            is ObjectControl -> updateObjectControlInTree(item, control)
            is ArrayControl -> updateArrayControlInTree(item, control)
        }
        item.children.forEach { item ->
            (item.value as? ControlTreeItemData)?.also { updateTree(item as FilterableTreeItem<TreeItemData>, it.typeControl) }
        }
    }

    private fun updateObjectControlInTree(item: FilterableTreeItem<TreeItemData>, control: ObjectControl) {
        if (item.isLeaf) {
            if (control.childControls.isNotEmpty()) {
                addObjectControlChildren(item, control)
            }
        } else {
            if (control.childControls.isEmpty()) {
                item.clear()
            }
        }
    }

    private fun updateArrayControlInTree(item: FilterableTreeItem<TreeItemData>, control: TypeControl) {
        while (item.list.size > control.childControls.size) {
            item.list.removeLast()
        }
        item.addAll((item.list.size until control.childControls.size).map { wrapControlInTreeItem(control.childControls[it]) })
    }

    private fun addObjectControlChildren(node: FilterableTreeItem<TreeItemData>, control: ObjectControl) {
        if (viewOptions.groupBy == PropertyGrouping.REQUIRED) {
            addRequiredAndOptionalChildren(node, control.requiredChildren, control.optionalChildren)
        } else {
            val propOrder = control.model.schema.getPropertyOrder()

            node.addAll(control.childControls.sortedWith { o1, o2 ->
                val prop1 = o1.model.schema.getPropertyName()
                val prop2 = o2.model.schema.getPropertyName()

                val index1 = propOrder.indexOf(prop1).let { if (it == -1) Int.MAX_VALUE else it }
                val index2 = propOrder.indexOf(prop2).let { if (it == -1) Int.MAX_VALUE else it }

                val compareOrdered = index1.compareTo(index2)

                if (compareOrdered != 0) {
                    compareOrdered
                } else {
                    o1.model.schema.title.toLowerCase().compareTo(o2.model.schema.title.toLowerCase())
                }
            }.map { wrapControlInTreeItem(it) })
        }
    }

    private fun createRequiredHeader(): FilterableTreeItem<TreeItemData> = FilterableTreeItem(HeaderTreeItemData("Required"))

    private fun createOptionalHeader(): FilterableTreeItem<TreeItemData> = FilterableTreeItem(HeaderTreeItemData("Optional"))

    private fun addRequiredAndOptionalChildren(node: FilterableTreeItem<TreeItemData>, required: List<TypeControl>, optional: List<TypeControl>) {
        if (required.isNotEmpty()) {
            node.add(createRequiredHeader())
            node.addAll(required.map { wrapControlInTreeItem(it) })
        }

        if (optional.isNotEmpty()) {
            node.add(createOptionalHeader())
            node.addAll(optional.map { wrapControlInTreeItem(it) })
        }
    }

    private fun updateTreeUiElements(root: FilterableTreeItem<TreeItemData>, data: JSONObject) {
        val decorator = GraphicValidationDecoration()
        val errorMap = validate(prepareForValidation(root, data))
        flatten(root).forEach { item ->
            item.value?.actions?.updateDisablement()
            (item.value as? ControlTreeItemData)?.also { data ->
                decorator.removeDecorations(data.label)
                createValidationMessage(data.label,
                        errorMap[listOf("#") + data.typeControl.model.schema.pointer]
                )?.also(decorator::applyValidationDecoration)
            }
        }
        decorator.removeDecorations(treeItem.value.label)
        createValidationMessage(treeItem.value.label, errorMap[listOf("#")])?.also(decorator::applyValidationDecoration)
    }

    private fun validate(data: JSONObject): Map<List<String>, String> {
        return (try {
            schema.baseSchema.validate(data)
            valid.set(true)
            null
        } catch (e: ValidationException) {
            valid.set(false)
            e
        })?.let(::mapPointerToError) ?: emptyMap()
    }

    private fun prepareForValidation(root: FilterableTreeItem<TreeItemData>, data: JSONObject): JSONObject {
        val copy = deepCopyForJson(data)
        flatten(root).map { it.value }.filterIsInstance<ControlTreeItemData>().forEach {
            val schema = it.typeControl.model.schema
            val defaultValue = schema.defaultValue
            if (defaultValue != null) {
                val pointer = schema.pointer
                val parent = JSONPointer(pointer.dropLast(1)).queryFrom(copy) as? JSONObject
                if (parent != null && !parent.has(pointer.last())) {
                    parent.put(pointer.last(), schema.defaultValue)
                }
            }
        }
        return copy
    }

    private fun <T> flatten(item: FilterableTreeItem<T>): Sequence<FilterableTreeItem<T>> =
            item.list.asSequence().flatMap { flatten(it) } + sequenceOf(item)

    private fun mapPointerToError(ex: ValidationException): Map<List<String>, String> {
        fun flatten(ex: ValidationException): Sequence<ValidationException> =
                if (ex.causingExceptions.isEmpty()) sequenceOf(ex)
                else ex.causingExceptions.asSequence().flatMap { flatten(it) }

        val ret = mutableMapOf<List<String>, String>()
        fun addError(pointer: List<String>, message: String) {
            val errorsSoFar = ret[pointer]
            ret[pointer] = if (errorsSoFar == null) message else errorsSoFar + "\n" + message
        }

        val parentErrorCount = mutableMapOf<List<String>, Int>()

        flatten(ex).forEach { validationError ->
            val pointers = validationError.pointerToViolation.split('/').heads()
            pointers.dropLast(1).forEach { parentPointer ->
                val count = parentErrorCount[parentPointer] ?: 0
                parentErrorCount[parentPointer] = count + 1
            }
            addError(pointers.last(), validationError.errorMessage)
        }
        parentErrorCount.forEach { (k, v) -> addError(k, "$v sub-error" + if (v > 1) "s" else "") }

        return ret
    }

    private fun createValidationMessage(label: Label, msg: String?): SimpleValidationMessage? =
            msg?.let { SimpleValidationMessage(label, it, Severity.ERROR) }

    class SimpleValidationMessage(
            private val target: Control,
            private val text: String,
            private val severity: Severity
    ) : ValidationMessage {
        override fun getTarget(): Control = target
        override fun getText(): String = text
        override fun getSeverity(): Severity = severity
    }

    private inner class ContentHandler(var data: JSONObject) {
        private var dataDirty = true

        fun handleExpansion() {
            if (objectControl == null) {
                initObjectControl()
            }
            if (dataDirty) {
                fillSheet(data)
                dataDirty = false
            }
        }

        fun updateData(data: JSONObject) {
            this.data = data
            if (treeItem.isExpanded) {
                fillSheet(data)
            } else {
                dataDirty = true
            }
        }
    }
}

fun <T> List<T>.heads(): List<List<T>> {
    tailrec fun <T> headsRec(list: List<T>, heads: List<List<T>>): List<List<T>> = when {
        list.isEmpty() -> heads
        else -> headsRec(list.dropLast(1), listOf(list) + heads)
    }
    return headsRec(this, emptyList())
}

private fun <T> deepCopyForJson(obj: T): T = when (obj) {
    is JSONObject -> obj.keySet().fold(JSONObject()) { acc, it ->
        acc.put(it, deepCopyForJson(obj.get(it)))
    } as T
    is JSONArray -> obj.fold(JSONArray()) { acc, it ->
        acc.put(deepCopyForJson(it))
    } as T
    else -> obj
}
