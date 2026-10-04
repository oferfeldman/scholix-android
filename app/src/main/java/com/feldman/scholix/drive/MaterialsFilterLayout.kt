package com.feldman.scholix.drive

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

data class MaterialFilter(val id:String,val side:String="top")
object MaterialsFilters {
    val labels=linkedMapOf("followed" to "Course folders","shared" to "Shared with me","root" to "My Drive","star" to "StarNote","offline" to "Offline")
    val sides=listOf("top","bottom","left","right")
    val defaults get()=labels.keys.map {MaterialFilter(it)}
    fun normalize(filters:List<MaterialFilter>):List<MaterialFilter> {
        val valid=filters.filter {it.id in labels && it.side in sides}.distinctBy {it.id}
        return valid+defaults.filter {d->valid.none {it.id==d.id}}
    }
    fun read(value:String)=runCatching {val a=JSONArray(value);normalize((0 until a.length()).map {a.getJSONObject(it).let {j->MaterialFilter(j.getString("id"),j.getString("side"))}})}.getOrDefault(defaults)
    fun encode(filters:List<MaterialFilter>)=JSONArray().apply {normalize(filters).forEach {put(JSONObject().put("id",it.id).put("side",it.side))}}.toString()
}

@Composable
fun MaterialFilterLayout(filters:List<MaterialFilter>,active:String,visible:Boolean,onSelect:(String)->Unit,
    modifier:Modifier=Modifier,content:@Composable ()->Unit) {
    @Composable fun strip(side:String) {
        val group=filters.filter {it.side==side}
        if(!visible||group.isEmpty())return
        @Composable fun chips() {group.forEach {f->
            FilterChip(active==f.id,onClick={onSelect(f.id)},label={Text(MaterialsFilters.labels.getValue(f.id))},
                modifier=if(side in listOf("left","right"))Modifier.fillMaxWidth() else Modifier)
        }}
        if(side in listOf("top","bottom"))Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){chips()}
        else Column(Modifier.width(108.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal=4.dp)){chips()}
    }
    Column(modifier) {
        strip("top")
        Row(Modifier.weight(1f).fillMaxWidth()) {
            strip("left")
            Box(Modifier.weight(1f).fillMaxHeight()){content()}
            strip("right")
        }
        strip("bottom")
    }
}

@Composable
fun MaterialsFilterDialog(filters:List<MaterialFilter>,onDismiss:()->Unit,onSave:(List<MaterialFilter>)->Unit) {
    var draft by remember {mutableStateOf(filters)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Arrange filters")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Choose an edge for each filter. Arrows change the order along that edge.")
            draft.forEachIndexed {index,f->
                var expanded by remember(f.id){mutableStateOf(false)}
                Column {
                    Row {
                        Text(MaterialsFilters.labels.getValue(f.id),Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                        IconButton(onClick={draft=draft.toMutableList().also {java.util.Collections.swap(it,index,index-1)}},enabled=index>0,modifier=Modifier.size(32.dp)) {Icon(Icons.Default.KeyboardArrowUp,"Move ${MaterialsFilters.labels[f.id]} earlier")}
                        IconButton(onClick={draft=draft.toMutableList().also {java.util.Collections.swap(it,index,index+1)}},enabled=index<draft.lastIndex,modifier=Modifier.size(32.dp)) {Icon(Icons.Default.KeyboardArrowDown,"Move ${MaterialsFilters.labels[f.id]} later")}
                    }
                    Box {
                        OutlinedButton(onClick={expanded=true}){Text("Position: ${f.side.replaceFirstChar {it.uppercase()}}")}
                        DropdownMenu(expanded,onDismissRequest={expanded=false}) {MaterialsFilters.sides.forEach {side->
                            DropdownMenuItem(text={Text(side.replaceFirstChar {it.uppercase()})},onClick={draft=draft.map {if(it.id==f.id)it.copy(side=side) else it};expanded=false})
                        }}
                    }
                }
            }
            TextButton(onClick={draft=MaterialsFilters.defaults}) {Text("Restore defaults")}
        }
    },confirmButton={TextButton(onClick={onSave(draft)}){Text("Apply")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
