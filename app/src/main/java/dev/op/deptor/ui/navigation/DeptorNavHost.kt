package dev.op.deptor.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import dev.op.deptor.domain.invite.Invite
import dev.op.deptor.domain.invite.InviteLink
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.domain.model.MemberId
import dev.op.deptor.domain.model.Money
import dev.op.deptor.ui.expense.AddExpenseScreen
import dev.op.deptor.ui.group.GroupDetailScreen
import dev.op.deptor.ui.groups.GroupsScreen
import dev.op.deptor.ui.invite.JoinGroupScreen
import dev.op.deptor.ui.member.MemberDetailScreen
import dev.op.deptor.ui.members.MembersScreen
import dev.op.deptor.ui.recurrence.RecurrencesScreen

/**
 * Cesty aplikace.
 *
 * Argumenty jsou v cestě jako řetězce a do doménových typů se převádějí až ve
 * ViewModelu — navigační knihovna umí jen primitivy a protahovat value class přes
 * `SavedStateHandle` by přineslo víc složitosti než užitku.
 */
object Routes {
    const val GROUPS = "groups"

    const val GROUP_ID = "groupId"
    const val GROUP_DETAIL = "groups/{$GROUP_ID}"
    fun groupDetail(id: GroupId) = "groups/${id.value}"

    const val ADD_EXPENSE = "groups/{$GROUP_ID}/expense"
    fun addExpense(id: GroupId) = "groups/${id.value}/expense"

    const val MEMBERS = "groups/{$GROUP_ID}/members"
    fun members(id: GroupId) = "groups/${id.value}/members"

    const val RECURRENCES = "groups/{$GROUP_ID}/recurrences"
    fun recurrences(id: GroupId) = "groups/${id.value}/recurrences"

    const val MEMBER_ID = "memberId"
    const val MEMBER_DETAIL = "groups/{$GROUP_ID}/members/{$MEMBER_ID}"
    fun memberDetail(groupId: GroupId, memberId: MemberId) =
        "groups/${groupId.value}/members/${memberId.value}"

    /**
     * Přijetí pozvánky. Cesta odpovídá tvaru odkazu z [InviteLink], aby ji uměl
     * otevřít i deep link zvenčí.
     */
    const val INVITE_CODE = "inviteCode"
    const val JOIN = "join/{$GROUP_ID}/{$INVITE_CODE}"
    fun join(invite: Invite) = "join/${invite.groupId.value}/${invite.code}"

    /**
     * Vyrovnání otevře formulář výdaje předvyplněný podle návrhu ze `simplify()`.
     * Argumenty jsou volitelné — bez nich jde o běžné ruční vyrovnání.
     */
    const val SETTLE_FROM = "from"
    const val SETTLE_TO = "to"
    const val SETTLE_AMOUNT = "amount"
    const val SETTLE = "groups/{$GROUP_ID}/settle?$SETTLE_FROM={$SETTLE_FROM}&$SETTLE_TO={$SETTLE_TO}&$SETTLE_AMOUNT={$SETTLE_AMOUNT}"
    fun settle(groupId: GroupId, from: MemberId, to: MemberId, amount: Money) =
        "groups/${groupId.value}/settle?$SETTLE_FROM=${from.value}&$SETTLE_TO=${to.value}&$SETTLE_AMOUNT=${amount.minor}"
}

@Composable
fun DeptorNavHost(
    pendingInvite: Invite? = null,
    onInviteHandled: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    // Pozvánka, která dorazila před přihlášením, se uplatní tady — graf už existuje.
    LaunchedEffect(pendingInvite) {
        if (pendingInvite != null) {
            navController.navigate(Routes.join(pendingInvite))
            onInviteHandled()
        }
    }

    NavHost(navController = navController, startDestination = Routes.GROUPS) {

        composable(Routes.GROUPS) {
            GroupsScreen(
                onOpenGroup = { navController.navigate(Routes.groupDetail(it)) },
            )
        }

        composable(
            route = Routes.GROUP_DETAIL,
            arguments = listOf(navArgument(Routes.GROUP_ID) { type = NavType.StringType }),
        ) {
            GroupDetailScreen(
                onBack = navController::popBackStack,
                onAddExpense = { navController.navigate(Routes.addExpense(it)) },
                onManageMembers = { navController.navigate(Routes.members(it)) },
                onShowRecurrences = { navController.navigate(Routes.recurrences(it)) },
                onSettle = { groupId, transfer ->
                    navController.navigate(
                        Routes.settle(groupId, transfer.from, transfer.to, transfer.amount),
                    )
                },
                onOpenMember = { groupId, memberId ->
                    navController.navigate(Routes.memberDetail(groupId, memberId))
                },
            )
        }

        composable(
            route = Routes.ADD_EXPENSE,
            arguments = listOf(navArgument(Routes.GROUP_ID) { type = NavType.StringType }),
        ) {
            AddExpenseScreen(onDone = navController::popBackStack)
        }

        composable(
            route = Routes.SETTLE,
            arguments = listOf(
                navArgument(Routes.GROUP_ID) { type = NavType.StringType },
                navArgument(Routes.SETTLE_FROM) { nullable = true; defaultValue = null },
                navArgument(Routes.SETTLE_TO) { nullable = true; defaultValue = null },
                navArgument(Routes.SETTLE_AMOUNT) { nullable = true; defaultValue = null },
            ),
        ) {
            AddExpenseScreen(onDone = navController::popBackStack)
        }

        composable(
            route = Routes.MEMBERS,
            arguments = listOf(navArgument(Routes.GROUP_ID) { type = NavType.StringType }),
        ) {
            MembersScreen(onBack = navController::popBackStack)
        }

        composable(
            route = Routes.JOIN,
            arguments = listOf(
                navArgument(Routes.GROUP_ID) { type = NavType.StringType },
                navArgument(Routes.INVITE_CODE) { type = NavType.StringType },
            ),
            // Deep link ze sdíleného odkazu vede rovnou sem.
            deepLinks = listOf(
                navDeepLink {
                    uriPattern = "${InviteLink.SCHEME}://${InviteLink.HOST}/{${Routes.GROUP_ID}}/{${Routes.INVITE_CODE}}"
                },
            ),
        ) {
            JoinGroupScreen(
                onJoined = { groupId ->
                    navController.navigate(Routes.groupDetail(groupId)) {
                        popUpTo(Routes.GROUPS)
                    }
                },
                onCancel = {
                    navController.navigate(Routes.GROUPS) { popUpTo(Routes.GROUPS) }
                },
            )
        }

        composable(
            route = Routes.RECURRENCES,
            arguments = listOf(navArgument(Routes.GROUP_ID) { type = NavType.StringType }),
        ) {
            RecurrencesScreen(onBack = navController::popBackStack)
        }

        composable(
            route = Routes.MEMBER_DETAIL,
            arguments = listOf(
                navArgument(Routes.GROUP_ID) { type = NavType.StringType },
                navArgument(Routes.MEMBER_ID) { type = NavType.StringType },
            ),
        ) {
            MemberDetailScreen(onBack = navController::popBackStack)
        }
    }
}
