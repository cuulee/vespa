// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchlib.rankingexpression.evaluation;

import com.yahoo.searchlib.rankingexpression.RankingExpression;
import com.yahoo.searchlib.rankingexpression.evaluation.gbdtoptimization.GBDTForestOptimizer;
import com.yahoo.searchlib.rankingexpression.evaluation.gbdtoptimization.GBDTOptimizer;

/**
 * This class will perform various optimizations on the ranking expressions. Clients using optimized expressions
 * will do
 *
 * <code>
 * // Set up once
 * RankingExpression expression = new RankingExpression(myExpressionString);
 * ArrayContext context = new ArrayContext(expression);
 * new ExpressionOptimizer().optimize(expression, context);
 *
 * // Execute repeatedly
 * context.put("featureName1", value1);
 * ...
 * expression.evaluate(context);
 *
 * // Note that the expression may be used by multiple threads at the same time, while the
 * // context is single-threaded. To create a context for another tread, use the above context as a prototype,
 * // contextForOtherThread = context.clone();
 * </code>
 * <p>
 * Instances of this class are not multithread safe.
 *
 * @author bratseth
 */
public class ExpressionOptimizer {

    private GBDTOptimizer gbdtOptimizer = new GBDTOptimizer();

    private GBDTForestOptimizer gbdtForestOptimizer = new GBDTForestOptimizer();

    /** Gets an optimizer instance used by this by class name, or null if the optimizer is not known */
    public Optimizer getOptimizer(Class<?> clazz) {
        if (clazz == gbdtOptimizer.getClass())
            return gbdtOptimizer;
        if (clazz == gbdtForestOptimizer.getClass())
            return gbdtForestOptimizer;
        return null;
    }

    public OptimizationReport optimize(RankingExpression expression, ContextIndex arrayContext) {
        OptimizationReport report = new OptimizationReport();
        // Note: Order of optimizations matter
        gbdtOptimizer.optimize(expression, arrayContext, report);
        gbdtForestOptimizer.optimize(expression, arrayContext, report);
        return report;
    }

}
