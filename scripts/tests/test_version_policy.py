import unittest
from scripts.check_version_policy import apply_version, parse_version, validate


class VersionPolicyTest(unittest.TestCase):
    def test_allowed_transitions(self):
        for base, head, new in [('main', 'development', (0, 14, 4)),
                                ('main', 'hotfix/map', (0, 13, 5)),
                                ('development', 'feat/routes', (0, 13, 5)),
                                ('development', 'fix/map', (0, 13, 5))]:
            with self.subTest(base=base, head=head):
                validate(base, head, (0, 13, 4), new, 20, 21)

    def test_reject_invalid_routes_and_versions(self):
        for base, head, new, code in [('main', 'feat/routes', (0, 13, 5), 21),
                                      ('main', 'fix/map', (0, 13, 5), 21),
                                      ('development', 'hotfix/map', (0, 13, 5), 21),
                                      ('main', 'other', (0, 13, 5), 21),
                                      ('other', 'fix/map', (0, 13, 5), 21),
                                      ('main', 'development', (0, 14, 0), 21),
                                      ('main', 'hotfix/map', (1, 13, 4), 21),
                                      ('development', 'feat/routes', (0, 13, 4), 21),
                                      ('main', 'hotfix/map', (0, 13, 5), 20)]:
            with self.subTest(base=base, head=head, new=new, code=code):
                with self.assertRaises(ValueError):
                    validate(base, head, (0, 13, 4), new, 20, code)

    def test_explicit_major_approval(self):
        validate('main', 'hotfix/major', (0, 13, 4), (1, 13, 4), 20, 21, True)
        with self.assertRaises(ValueError):
            validate('main', 'feat/major', (0, 13, 4), (1, 13, 4), 20, 21, True)

    def test_promotion_keeps_destination_patch(self):
        validate('main', 'development', (0, 13, 6), (0, 14, 6), 22, 24)
        with self.assertRaises(ValueError):
            validate('main', 'development', (0, 13, 6), (0, 13, 7), 22, 23)

    def test_apply_version_rewrites_gradle_and_readme(self):
        gradle = '    defaultConfig {\n        versionName = "0.13.7"\n        versionCode = 23\n    }\n'
        readme = 'The current checkout is version 0.13.7. The 0.13 series adds voices.\n'
        gradle, readme = apply_version(gradle, readme, (0, 14, 6), 24)
        self.assertEqual(parse_version(gradle), ((0, 14, 6), 24))
        self.assertIn('        versionCode = 24\n', gradle)
        self.assertEqual(readme, 'The current checkout is version 0.14.6. The 0.13 series adds voices.\n')


if __name__ == '__main__':
    unittest.main()
