package dev.brights0ng.enginesandempires.oregen;

/**
 * A deposit after it has been checked against the terrain: what became of it, and its body if it exists.
 *
 * @param deposit the deposit as the ore map places it
 * @param outcome what the resolver decided, including which draw was accepted
 * @param body    the accepted body, or null if the deposit was dropped for having no rock to be in
 */
public record ResolvedDeposit(Deposit deposit, DepositResolver.Outcome outcome, DepositBody body) {

    /** True if the deposit exists, that is, was not dropped as having no rock to be in. */
    public boolean viable() {
        return outcome.viable();
    }
}
