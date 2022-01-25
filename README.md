# ExpressAPR

This is the replication package of ExpressAPR, an accelerated APR patch validator on Java.

## What is inside?

- `src/`:
  Source code of ExpressAPR.
- `expapr-jar/`:
  ExpressAPR executable.
- `apr_tools/`:
  Candidate patches collected from studied APR approaches.
- `runner/`:
  A Python script for running the experiment that handles much dirty stuff
  (e.g., collecting logs, fixing classpaths, killing timed-out processes, limiting CPU usage, checking out projects...).
- `uniapr/`:
  Necessary files to compare our work against UniAPR.
- `docs/`:
  Documentation that describes the structure of the current implementation.

## Dependencies

The ExpressAPR itself is written in pure Java, so it does not depend on a certain benchmark or operating system.
The patch compilation step requires JDK 1.8+.
The test execution step should work for projects with JUnit 3.x or 4.x test cases on Java 1.5+.

To reproduce our experiment result, we recommend the Python script.
It currently only works for Defects4J bugs on Linux.
These are dependencies of the script:

- A Linux machine with at least 8 CPU cores
  - We tested on Ubuntu 20.04 and 16.04.
  - ***Why 8 cores?***
    By default, 8 workers are run in parallel, each using an exclusive core.
    If your machine doesn't have enough cores, change `N_WORKERS` in `runner/const.py`.
- JDK 1.8
  - `apt install openjdk-8-jdk`
  - Make sure `javac` reports error in English, because we parse its output to detect errors.
- Python 3.7+
  - `apt install python3 python3-pip` (on Ubuntu 20.04)
- CGroup
  - `apt install cgroup-bin cgroup-tools`
- 8 copies of Defects4J
  - Follow [their setup instruction](https://github.com/rjust/defects4j) and copy the installed path to 8 locations.
  - ***Why 8 copies?***
    To compile runtime files, the script modifies classpath in `build.xml`, which
    is unfortunately located in the global Defects4J path.
    Therefore, Defects4J path should be distinct for each worker.

To compare ExpressAPR against UniAPR (as in our experiment), you also need:

- UniAPR
  - Follow [their setup instruction](https://github.com/lingming/UniAPR).
- Maven 3.3.9
  - We experienced issues with UniAPR on the latest Maven version (reported to UniAPR authors). Maven 3.3.9 seems fine.

## Reproducing the Experiment 

To reproduce our experiment with all patches colleted from studied APR approaches:

- Extract patches
  - `apt install p7zip-full`
  - `cd apr_tools && 7za x collected_patches.7z && cd ..`
- `cd runner && pip3 install -r requirements.txt`
- Edit `const.py`
  - Change `D4J_PATH` to your Defects4J installation path. 
- `./init_cpuset.sh`
- `./run_naive.sh`
  - Result will be saved to `res-out/<APR_NAME>-naive-uniform-res.csv`.
- `./run_expressapr.sh`
  - Result will be saved to `res-out/<APR_NAME>-expressapr-<MODE>-res.csv`.
- `./run_expressapr_reporter.sh`
  - Result will be saved to `verbose-report-<APR_NAME>/`.
- `./run_uniapr.sh`
  - Result will be saved to `res-out/<APR_NAME>-uniapr-res.csv`.

Note that it may take a month or so to evaluate on all collected patches.
It is possible to run experiments on a subset.
To do so, manually delete some files in `apr_tools/` and re-run the scripts above.

