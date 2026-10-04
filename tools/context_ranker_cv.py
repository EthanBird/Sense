"""Source-family calibration of the fixed context-only model, no dev-driven tuning."""
import hashlib
import math

from context_only_ranker import CAP, SCHEMA, ContextScorer, arrays, feature_vocabulary, objective
from train_candidate_ranker import distance


def family_fold(family, seed, count=3):
    if not family or not seed or type(count) is not int or count < 2:
        raise ValueError('Invalid source-family fold specification')
    return int(hashlib.sha256((seed+'\t'+family).encode('utf-8')).hexdigest(),16) % count


def split_groups(groups, families, seed, fold, count=3):
    if not 0 <= fold < count:
        raise ValueError('Invalid validation fold')
    train=[];validation=[]
    for g in groups:
        (validation if family_fold(families[g['id']],seed,count)==fold else train).append(g)
    if not train or not validation:
        raise ValueError('Empty fitting/validation partition')
    a={families[g['id']] for g in train};b={families[g['id']] for g in validation}
    if a & b:raise ValueError('Source-family leakage')
    return train,validation


def fit_regularized(groups, families, ridge, prepared=None):
    import numpy as np
    from scipy.optimize import minimize
    if not math.isfinite(ridge) or ridge <= 0:
        raise ValueError('Positive finite ridge required')
    keys,data = prepared if prepared is not None else prepare(groups,families)
    start=np.zeros(len(keys),dtype=np.float64)
    history=[]
    result=minimize(objective,start,args=(*data,ridge),jac=True,method='L-BFGS-B',
        callback=lambda w:history.append(objective(w,*data,ridge)[0]),
        options={'maxiter':200,'ftol':1e-10,'gtol':1e-7})
    model=dict(schema=SCHEMA,cap=CAP,vocabulary=keys,weights=result.x.tolist())
    ContextScorer(model)
    return model,dict(ridge=ridge,parameters=len(keys),groups=len(groups),candidateRows=len(data[1]),
        iterations=int(result.nit),evaluations=int(result.nfev),converged=bool(result.success),message=str(result.message),
        initialLoss=objective(start,*data,ridge)[0],finalLoss=float(result.fun),lossHistory=history)


def prepare(groups,families):
    keys=feature_vocabulary(groups,families)
    if not keys:raise ValueError('No fitting-family context vocabulary')
    return keys,arrays(groups,keys)


def restored_order(group,scores):
    texts,slots,original=group['texts'],group['slots'],group['originalTop8']
    if (len(scores)!=len(texts) or len(slots)!=len(texts) or len(original)>8
            or slots!=sorted(set(slots)) or not slots or slots[0]!=0
            or any(type(i) is not int or i<0 or i>=len(original) for i in slots)
            or [original[i] for i in slots]!=texts or len(set(original))!=len(original)
            or not all(math.isfinite(s) for s in scores)):
        raise ValueError('Invalid original candidate slot evidence')
    result=list(original)
    for target,source in zip(slots,sorted(range(len(scores)),key=lambda i:(-scores[i],i))):
        result[target]=texts[source]
    return result


def evaluate_groups(groups,scorer):
    records=[]
    for g in groups:
        before=g['originalTop8'];gold=g['texts'][g['gold']]
        scores=scorer(g['context'],g['texts'],g['evidence'])
        after=restored_order(g,scores)
        records.append(dict(id=g['id'],cut=g['cut'],expected=gold,context=g['context'],
            beforeTop8=before,afterTop8=after,beforeRank=before.index(gold)+1,afterRank=after.index(gold)+1,
            beforeErrors=distance(before[0],gold),afterErrors=distance(after[0],gold),scores=scores))
    return records,metrics(records)


def metrics(records):
    result={}
    for side in ['before','after']:
        result[side]=dict(states=len(records),top1=sum(r[side+'Rank']==1 for r in records),
            top5=sum(r[side+'Rank']<=5 for r in records),
            characterErrors=sum(r[side+'Errors'] for r in records),
            characters=sum(len(r['expected']) for r in records))
    return result


def select_ridge(reports):
    eligible=[]
    reference=None
    for r in reports:
        b,a=r['metrics']['before'],r['metrics']['after']
        if reference is None:reference=b
        if b!=reference or a['states']!=b['states'] or a['characters']!=b['characters']:
            raise ValueError('Unpaired cross-validation workload')
        if r['allConverged'] and a['top1']>b['top1'] and a['top5']>=b['top5'] and a['characterErrors']<=b['characterErrors']:
            eligible.append(r)
    if not eligible:return None
    return max(eligible,key=lambda r:(r['metrics']['after']['top1'],-r['metrics']['after']['characterErrors'],
                                     r['metrics']['after']['top5'],r['ridge']))['ridge']
