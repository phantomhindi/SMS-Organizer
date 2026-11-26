package com.binay.smsorganizer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Cat { OTP, MONEY, BILLS, PROMO, PERSONAL, OTHER }
enum class Tab(val label: String) { PERSONAL("Personal"), TXN("Transactions"), PROMO("Promotions"), STAR("Starred") }
fun Cat.tab() = when (this) {
    Cat.PERSONAL -> Tab.PERSONAL
    Cat.OTP, Cat.MONEY, Cat.BILLS -> Tab.TXN
    else -> Tab.PROMO
}

data class Sms(val id: Long, val address: String, val body: String, val date: Long, val read: Boolean, val cat: Cat)
data class Acct(val name: String, val bal: Double, val date: Long)
data class Rem(val sms: Sms, val due: Long, val total: String?, val min: String?, val label: String)
data class Txn(val sms: Sms, val amount: Double, val credit: Boolean, val label: String)

private val otpRx = Regex("(?i)\\b(otp|one[- ]time|verification code|passcode|security code)\\b")
private val moneyRx = Regex("(?i)(debited|credited|withdrawn|spent|paid|received|upi|txn|a/c|acct)")
private val billRx = Regex("(?i)(bill|due date|due on|due by|recharge|premium|statement|payment due)")
private val promoRx = Regex("(?i)(offer|sale|discount|% ?off|cashback|coupon|limited time|t&c|http|www\\.)")
private val amountRx = Regex("(?i)(?:rs\\.?|inr|₹)\\s?([\\d,]+(?:\\.\\d+)?)")
private val balRx = Regex("(?i)(?:avl\\.?\\s*bal(?:ance)?|available bal(?:ance)?|bal(?:ance)?)\\D{0,15}?(?:rs\\.?|inr|₹)\\s?([\\d,]+(?:\\.\\d+)?)")
private val acctRx = Regex("(?i)(?:a/c|acct?|account|card)(?:\\s*(?:no\\.?|ending(?: with)?))?\\s*[xX*]*\\s*(\\d{2,4})\\b")
private val dueRx = Regex("(?i)(?:due\\s*(?:date|on|by)?|payable by|pay by|last date)\\s*[:\\-]?\\s*(\\d{1,2}[-/ ](?:[A-Za-z]{3}|\\d{2})[-/ ]\\d{2,4})")
private val phoneRx = Regex("^\\+?[\\d\\s-]{8,}$")
private val totalRx = Regex("(?i)total (?:amt|amount) due\\D{0,6}?(?:rs\\.?|inr|₹)\\s?([\\d,]+(?:\\.\\d+)?)")
private val minRx = Regex("(?i)min(?:imum)?\\.? (?:amt|amount) due\\D{0,6}?(?:rs\\.?|inr|₹)\\s?([\\d,]+(?:\\.\\d+)?)")
private val debitRx = Regex("(?i)\\b(debited|spent|withdrawn|paid|purchase|sent)\\b")
private val creditRx = Regex("(?i)\\b(credited|received|deposited|refund)")
private val merchRx = Regex("(?i)(?:\\bat|\\bto|\\bfrom|info[:\\-]|vpa)\\s+([A-Za-z][A-Za-z0-9 .&@_*-]{2,28}?)\\s*(?=\\bon\\b|\\bref\\b|\\bvia\\b|\\bupi\\b|\\bavl\\b|[.,;(]|$)")
private val urlRx = Regex("https?://[^\\s;,]+")
private val fmts = listOf("dd-MMM-yyyy", "dd MMM yyyy", "dd/MM/yyyy", "dd-MM-yyyy", "dd-MMM-yy", "dd/MM/yy", "dd-MM-yy")

fun fmt(t: Long, p: String): String = SimpleDateFormat(p, Locale.getDefault()).format(Date(t))
fun num(s: String?) = s?.replace(",", "")?.toDoubleOrNull()

fun parseDate(s: String): Long? {
    for (f in fmts) try {
        val d = SimpleDateFormat(f, Locale.ENGLISH).apply { isLenient = false }.parse(s.trim())
        if (d != null) return d.time
    } catch (e: Exception) { }
    return null
}

fun classify(id: Long, a: String, b: String, d: Long, r: Boolean): Sms {
    val cat = when {
        otpRx.containsMatchIn(b) -> Cat.OTP
        moneyRx.containsMatchIn(b) && amountRx.containsMatchIn(b) -> Cat.MONEY
        billRx.containsMatchIn(b) -> Cat.BILLS
        promoRx.containsMatchIn(b) -> Cat.PROMO
        phoneRx.matches(a) || !a.any { it.isLetter() } -> Cat.PERSONAL
        else -> Cat.OTHER
    }
    return Sms(id, a, b, d, r, cat)
}

fun loadSms(ctx: Context): List<Sms> {
    val out = ArrayList<Sms>()
    ctx.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id", "address", "body", "date", "read"),
        null, null, "date DESC"
    )?.use { c ->
        while (c.moveToNext() && out.size < 5000)
            out.add(classify(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getInt(4) == 1))
    }
    return out
}

val banks = listOf("HDFC", "ICICI", "SBI", "AXIS", "KOTAK", "PNB", "BOB", "IDFC", "YES", "CANARA")

fun acctKey(m: Sms): String? {
    val last = acctRx.find(m.body)?.groupValues?.get(1) ?: return null
    val tok = m.address.split("-").let { if (it.size > 1) it[1] else it[0] }.uppercase()
    return "${banks.firstOrNull { tok.contains(it) } ?: tok} XX$last"
}

fun accounts(all: List<Sms>): List<Acct> {
    val seen = LinkedHashMap<String, Acct>()
    for (m in all) {
        if (m.cat != Cat.MONEY && m.cat != Cat.BILLS) continue
        val bal = num(balRx.find(m.body)?.groupValues?.get(1)) ?: continue
        val name = acctKey(m) ?: continue
        if (name !in seen) seen[name] = Acct(name, bal, m.date)
    }
    return seen.values.toList()
}

fun txns(all: List<Sms>, key: String): List<Txn> =
    all.filter { it.cat == Cat.MONEY && acctKey(it) == key }.mapNotNull { m ->
        val amt = num(amountRx.find(m.body)?.groupValues?.get(1)) ?: return@mapNotNull null
        val credit = creditRx.containsMatchIn(m.body)
        if (!credit && !debitRx.containsMatchIn(m.body)) return@mapNotNull null
        val label = if (credit) key.replace(" XX", " Bank A/c XX")
        else merchRx.find(m.body)?.groupValues?.get(1)?.trim() ?: key
        Txn(m, amt, credit, label)
    }

fun reminders(all: List<Sms>): List<Rem> =
    all.filter { it.cat == Cat.BILLS }.mapNotNull { m ->
        val due = dueRx.find(m.body)?.groupValues?.get(1)?.let { parseDate(it) } ?: return@mapNotNull null
        val total = totalRx.find(m.body)?.groupValues?.get(1) ?: amountRx.find(m.body)?.groupValues?.get(1)
        Rem(m, due, total, minRx.find(m.body)?.groupValues?.get(1), acctKey(m) ?: m.address)
    }

fun money(v: Double) = if (v % 1.0 == 0.0) "₹%,.0f".format(v) else "₹%,.2f".format(v)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { App() } }
        }
    }
}

@Composable
fun Avatar(t: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Text(t.take(1).uppercase(), style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("SMS Organizer", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Needs permission to read your messages. Everything stays on your device.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.READ_SMS) }) { Text("Allow") }
        }
        return
    }
    val prefs = remember { ctx.getSharedPreferences("sms", 0) }
    var starred by remember { mutableStateOf(prefs.getStringSet("star", emptySet())!!.toSet()) }
    val toggleStar = { id: Long ->
        starred = if (id.toString() in starred) starred - id.toString() else starred + id.toString()
        prefs.edit().putStringSet("star", starred).apply()
    }
    var all by remember { mutableStateOf<List<Sms>>(emptyList()) }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { loadSms(ctx) } }
    var nav by remember { mutableStateOf(0) }
    var thread by remember { mutableStateOf<String?>(null) }

    if (thread != null) {
        Thread(thread!!, all, starred, toggleStar) { thread = null }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (nav) {
                0 -> Messages(all, starred) { thread = it }
                1 -> RemindersScreen(all) { thread = it }
                else -> FinanceScreen(all)
            }
        }
        NavigationBar {
            listOf("💬" to "Messages", "⏰" to "Reminders", "💳" to "Finance").forEachIndexed { i, p ->
                NavigationBarItem(selected = nav == i, onClick = { nav = i }, icon = { Text(p.first) }, label = { Text(p.second) })
            }
        }
    }
}

@Composable
fun Messages(all: List<Sms>, starred: Set<String>, onThread: (String) -> Unit) {
    var tab by remember { mutableStateOf(Tab.PERSONAL) }
    var q by remember { mutableStateOf("") }
    val shown = remember(all, tab, q, starred) {
        all.filter {
            (if (tab == Tab.STAR) it.id.toString() in starred else it.cat.tab() == tab) &&
                (q.isBlank() || it.body.contains(q, true) || it.address.contains(q, true))
        }.groupBy { it.address }
    }
    Column(Modifier.padding(horizontal = 12.dp)) {
        Text("Messages", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp))
        OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true, label = { Text("Search") }, modifier = Modifier.fillMaxWidth())
        LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Tab.values().toList()) { t ->
                FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
            }
        }
        if (shown.isEmpty() && tab == Tab.STAR) Text("No starred messages yet. Open a conversation and tap ☆.")
        LazyColumn {
            items(shown.entries.toList(), key = { it.key }) { e ->
                val list = e.value
                val unread = list.count { !it.read }
                Row(Modifier.fillMaxWidth().clickable { onThread(e.key) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(e.key)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.key, maxLines = 1, style = MaterialTheme.typography.titleMedium, fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal)
                        Text(list[0].body, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmt(list[0].date, "dd MMM"), style = MaterialTheme.typography.labelSmall)
                        if (unread > 0) Badge { Text("$unread") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun Thread(addr: String, all: List<Sms>, starred: Set<String>, onStar: (Long) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val list = remember(all, addr) { all.filter { it.address == addr } }
    Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text(addr, style = MaterialTheme.typography.titleMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { m ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.body)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(fmt(m.date, "dd MMM yyyy, hh:mm a"), style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { onStar(m.id) }) { Text(if (m.id.toString() in starred) "★" else "☆") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RemindersScreen(all: List<Sms>, onThread: (String) -> Unit) {
    val ctx = LocalContext.current
    val rems = remember(all) { reminders(all) }
    val now = System.currentTimeMillis()
    val today = remember {
        Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    }
    var hist by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<Rem?>(null) }
    val list = remember(rems, hist) {
        if (hist) rems.filter { it.due < today }
        else rems.distinctBy { it.label }.filter { it.due >= today }.sortedBy { it.due }
    }
    Column(Modifier.padding(12.dp)) {
        Text("Reminders", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !hist, onClick = { hist = false }, label = { Text("All Reminders") })
            FilterChip(selected = hist, onClick = { hist = true }, label = { Text("History") })
        }
        if (list.isEmpty()) Text("Nothing here yet.", Modifier.padding(top = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp)) {
            items(list, key = { it.sms.id }) { r ->
                Card(Modifier.fillMaxWidth().clickable { sel = r }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(r.label, style = MaterialTheme.typography.titleMedium)
                            Text(fmt(r.due, "dd MMM yyyy"), style = MaterialTheme.typography.titleSmall)
                        }
                        Text("Bill amount", style = MaterialTheme.typography.bodySmall)
                        Text("₹${r.total ?: "-"}", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
    sel?.let { r ->
        val url = urlRx.find(r.sms.body)?.value?.trimEnd('.', ')')
        val d = Math.ceil((r.due - now) / 86400000.0).toInt()
        val inText = if (d < 0) "${-d} days ago" else if (d == 0) "Today" else "In $d days"
        AlertDialog(
            onDismissRequest = { sel = null },
            title = { Text(r.label) },
            text = {
                Column {
                    Text("₹${r.total ?: "-"}", style = MaterialTheme.typography.headlineSmall)
                    Text("${fmt(r.due, "dd MMM yyyy")} | $inText")
                    if (r.min != null) Text("₹${r.min} Min amt due", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = url != null, onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Pay Bill") }
            },
            dismissButton = { TextButton(onClick = { sel = null; onThread(r.sms.address) }) { Text("View SMS") } }
        )
    }
}

@Composable
fun FinanceScreen(all: List<Sms>) {
    val accts = remember(all) { accounts(all) }
    var show by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Acct?>(null) }
    if (open != null) {
        AccountDetail(open!!, all, show) { open = null }
        return
    }
    Column(Modifier.padding(12.dp)) {
        Text("Finance", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show balance", style = MaterialTheme.typography.titleMedium)
                Text("Balance read from SMS, actual may vary", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = show, onCheckedChange = { show = it })
        }
        LazyColumn {
            items(accts, key = { it.name }) { a ->
                Row(Modifier.fillMaxWidth().clickable { open = a }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(a.name, style = MaterialTheme.typography.titleMedium)
                        Text("Avbl bal on ${fmt(a.date, "dd MMM")}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (show) money(a.bal) else "*****", style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun AccountDetail(a: Acct, all: List<Sms>, show: Boolean, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var f by remember { mutableStateOf(0) }
    val tx = remember(all, a) { txns(all, a.name) }
    val shown = tx.filter { f == 0 || (f == 1) == it.credit }
    val green = Color(0xFF4CAF50)
    val red = Color(0xFFE57373)
    Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text(a.name, style = MaterialTheme.typography.titleLarge)
        }
        Text(if (show) "Balance ${money(a.bal)}" else "Balance *****", style = MaterialTheme.typography.titleMedium)
        Text("Updated on ${fmt(a.date, "dd MMM")}. Powered by SMS, actual details may vary.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Credit", "Debit").forEachIndexed { i, t ->
                FilterChip(selected = f == i, onClick = { f = i }, label = { Text(t) })
            }
        }
        LazyColumn {
            shown.groupBy { fmt(it.sms.date, "MMMM yyyy") }.forEach { (month, l) ->
                item { Text(month, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp)) }
                items(l, key = { it.sms.id }) { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(t.sms.date, "dd MMM"), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                        Text(t.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(money(t.amount), color = if (t.credit) green else red)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
