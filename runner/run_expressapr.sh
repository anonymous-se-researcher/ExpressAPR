#!/bin/bash

python3 collect_expressapr.py PlausibleRecoder normal
python3 collect_expressapr.py PlausibleTBar normal
python3 collect_expressapr.py PlausibleSimFix normal
python3 collect_expressapr.py PlausibleHanabi normal

python3 collect_expressapr.py PlausibleRecoder nodedup
python3 collect_expressapr.py PlausibleRecoder noprio

python3 collect_expressapr.py PlausibleTBar nodedup
python3 collect_expressapr.py PlausibleTBar noprio

python3 collect_expressapr.py PlausibleSimFix nodedup
python3 collect_expressapr.py PlausibleSimFix noprio

python3 collect_expressapr.py PlausibleHanabi nodedup
python3 collect_expressapr.py PlausibleHanabi noprio

python3 collect_expressapr.py PlausibleSimFix noboth
python3 collect_expressapr.py PlausibleHanabi noboth
python3 collect_expressapr.py PlausibleRecoder noboth
python3 collect_expressapr.py PlausibleTBar noboth
