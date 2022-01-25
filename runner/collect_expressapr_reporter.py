import patcher
import pathlib
import threading
from tqdm.contrib.concurrent import thread_map
from utils import cgidx_map
import traceback
import random
import reporter as rp
import d4j
import const
import sys

APR = sys.argv[1]
NODEDUP = False
THREADS = const.N_WORKERS
reporter_out_dir = pathlib.Path('verbose-report-'+(APR[len('Plausible'):] if APR.startswith('Plausible') else APR))

print('write into', reporter_out_dir)

file_lock = threading.Lock()
cgidx_lock = threading.Lock()

def proc_json(jsonpath):
    with cgidx_lock:
        tid = threading.get_ident()
        if tid not in cgidx_map:
            cgidx = len(cgidx_map)+1
            assert cgidx<=THREADS
            print('assign cgroup', cgidx, 'to thread', tid)
            cgidx_map[tid] = cgidx
        d4j.d4j_path_map[threading.get_ident()] = const.D4J_PATH[cgidx_map[tid]-1]

    def work():
        p = None
        try:
            rp.testkit_reporter.reset()
            rp.testkit_reporter.attach('json_path', str(jsonpath))

            rp.testkit_reporter.set_step('init')
            p = patcher.ExpAprPatcher(str(jsonpath), APR, nodedup=NODEDUP)
            rp.testkit_reporter.attach('total_patch_count', len(p.config.patches))

            rp.testkit_reporter.set_step('main')
            patchcnt, t_install, t_compile, t_run, succlist, inst_telemetry_cnts, run_telemetry_cnts = p.main()
            rp.testkit_reporter.attach('compiled_patch_count', patchcnt)

            rp.testkit_reporter.set_step('cleanup')
            p.cleanup()

            rp.testkit_reporter.set_step('done')
            t_all = t_install+t_compile+t_run
            rp.testkit_reporter.attach('timing_all', t_all)
            rp.testkit_reporter.attach('timing_install', t_install)
            rp.testkit_reporter.attach('timing_compilemain', t_compile)
            rp.testkit_reporter.attach('timing_run', t_run)
            rp.testkit_reporter.attach('succlist', succlist)
            rp.testkit_reporter.attach('inst_telemetry_cnts', inst_telemetry_cnts)
            rp.testkit_reporter.attach('run_telemetry_cnts', run_telemetry_cnts)

            return ['succ', str(jsonpath), patchcnt, t_install, t_compile, t_run, succlist, tree_handleable, telemetry_cnts]
        except Exception as e:
            rp.testkit_reporter.report_error(e)
            tbmsg = ''.join(traceback.format_exception(type(e), e, e.__traceback__))
            if p is not None:
                p.cleanup()
            return ['FAIL', str(jsonpath), tbmsg]

    res = work()

    projname = jsonpath.parent.parent.name.title()
    verbose = reporter_out_dir/projname
    verbose.mkdir(parents=True, exist_ok=True)
    with (verbose/(('succ-' if rp.testkit_reporter.succ else 'FAIL-')+str(jsonpath).replace('/', '$')+'.json')).open('w') as fv:
        rp.testkit_reporter.write(fv)

    # does not write timing into normal result file
    #with file_lock:
    #    with open(RES_FN, 'a', newline='') as f:
    #        w = csv.writer(f)
    #        w.writerow(res)

jsonpaths = list(pathlib.Path(f'../apr_tools/{APR}/patches-out').glob('**/*.json'))
random.seed(666)
random.shuffle(jsonpaths)
print('len:', len(jsonpaths))

rp.testkit_reporter = rp.ThreadDispatchingReporter(rp.ErrorReporter, 'threaded-testkit')

thread_map(proc_json, jsonpaths, max_workers=THREADS, miniters=1)