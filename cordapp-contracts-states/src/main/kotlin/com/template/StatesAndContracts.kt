package com.template

import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.*
import net.corda.core.crypto.NullKeys
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.contracts.asset.*
import net.corda.finance.utils.*
import java.util.*
import java.time.*

// *****************
// * Contract Code *
// *****************
// This is used to identify our contract when building a transaction
val TEMPLATE_CONTRACT_ID = "com.template.TemplateContract"

class CommercialPaper : Contract {

    //Comercial paper verify function
    override fun verify(tx: LedgerTransaction) {
        // Group by everything except owner: any modification to the CP at all is considered changing it fundamentally.
        val groups = tx.groupStates(State::withoutOwner)

        // There are two possible things that can be done with this CP. The first is trading it. The second is redeeming
        // it for cash on or after the maturity date.
        val command = tx.commands.requireSingleCommand<CommercialPaper.Commands>()

        val timeWindow: TimeWindow? = tx.timeWindow

        for ((inputs, outputs, _) in groups) {
            when (command.value) {
                is Commands.Move -> {
                    val input = inputs.single()
                    requireThat {
                        "the transaction is signed by the owner of the CP" using (input.owner.owningKey in command.signers)
                        "the state is propagated" using (outputs.size == 1)
                        // Don't need to check anything else, as if outputs.size == 1 then the output is equal to
                        // the input ignoring the owner field due to the grouping.
                    }
                }

                is Commands.Redeem -> {
                    // Redemption of the paper requires movement of on-ledger cash.
                    val input = inputs.single()
                    val received = tx.outputs.map { it.data }.sumCashBy(input.owner)
                    val time = timeWindow?.fromTime ?: throw IllegalArgumentException("Redemptions must be timestamped")
                    requireThat {
                        "the paper must have matured" using (time >= input.maturityDate)
                        "the received amount equals the face value" using (received == input.faceValue)
                        "the paper must be destroyed" using outputs.isEmpty()
                        "the transaction is signed by the owner of the CP" using (input.owner.owningKey in command.signers)
                    }
                }

                is Commands.Issue -> {
                    val output = outputs.single()
                    val time = timeWindow?.untilTime ?: throw IllegalArgumentException("Issuances must be timestamped")
                    requireThat {
                        // Don't allow people to issue commercial paper under other entities identities.
                        "output states are issued by a command signer" using (output.issuance.party.owningKey in command.signers)
                        "output values sum to more than the inputs" using (output.faceValue.quantity > 0)
                        "the maturity date is not in the past" using (time < output.maturityDate)
                        // Don't allow an existing CP state to be replaced by this issuance.
                        "can't reissue an existing state" using inputs.isEmpty()
                    }
                }

                else -> throw IllegalArgumentException("Unrecognised command")
            }
        }

    }

    //Comercial paper issuance process
    fun generateIssue(issuance: PartyAndReference, faceValue: Amount<Issued<Currency>>, maturityDate: Instant,
                      notary: Party): TransactionBuilder {
        val state = State(issuance, issuance.party, faceValue, maturityDate)
        val stateAndContract = StateAndContract(state, CP_PROGRAM_ID)
        return TransactionBuilder(notary = notary).withItems(stateAndContract, Command(Commands.Issue(), issuance.party.owningKey))
    }

    //Comercial paper move process
    fun generateMove(tx: TransactionBuilder, paper: StateAndRef<State>, newOwner: AbstractParty) {
        tx.addInputState(paper)
        val outputState = paper.state.data.withNewOwner(newOwner).ownableState
        tx.addOutputState(outputState, CP_PROGRAM_ID)
        tx.addCommand(Command(Commands.Move(), paper.state.data.owner.owningKey))
    }

    //Comercial paper redeem process
    @Throws(InsufficientBalanceException::class)
    fun generateRedeem(tx: TransactionBuilder, paper: StateAndRef<State>, services: ServiceHub) {
        // Add the cash movement using the states in our vault.
        Cash.generateSpend(
                services = services,
                tx = tx,
                amount = paper.state.data.faceValue.withoutIssuer(),
                to = paper.state.data.owner
        )
        tx.addInputState(paper)
        tx.addCommand(Command(Commands.Redeem(), paper.state.data.owner.owningKey))
    }

    //Comercial Paper Actions
    interface Commands : CommandData {
        class Move : TypeOnlyCommandData(), Commands
        class Redeem : TypeOnlyCommandData(), Commands
        class Issue : TypeOnlyCommandData(), Commands
    }

}

//open class TemplateContract : Contract {
//    // A transaction is considered valid if the verify() function of the contract of each of the transaction's input
//    // and output states does not throw an exception.
//    override fun verify(tx: LedgerTransaction) {
//        // Verification logic goes here.
//    }
//
//    // Used to indicate the transaction's intent.
//    interface Commands : CommandData {
//        class Action : Commands
//    }
//
//    //Comercial Paper Command
//    interface Commands : CommandData {
//        class Move : TypeOnlyCommandData(), Commands
//        class Redeem : TypeOnlyCommandData(), Commands
//        class Issue : TypeOnlyCommandData(), Commands
//    }
//}

// *********
// * State *
// *********
//data class TemplateState(val data: String) : ContractState {
//    override val participants: List<AbstractParty> get() = listOf()
//}

data class State(
        val issuance: PartyAndReference,
        override val owner: AbstractParty,
        val faceValue: Amount<Issued<Currency>>,
        val maturityDate: Instant
) : OwnableState {
    override val participants = listOf(owner)

    fun withoutOwner() = copy(owner = AnonymousParty(NullKeys.NullPublicKey))
    override fun withNewOwner(newOwner: AbstractParty) = CommandAndState(CommercialPaper.Commands.Move(), copy(owner = newOwner))
}