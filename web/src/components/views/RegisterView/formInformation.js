import * as Yup from 'yup';

export const initialFields = {
  firstName: '',
  lastName: '',
  email: '',
  username: '',
  password: '',
  confirm: '',
  terms: true,
  updatesAllowed: false,
};

export const placeholders = {
  firstName: 'Your first name',
  lastName: 'Your last name',
  email: 'Your email',
  username: 'The name you will be known',
  password: 'Write a strong password with more than 8 characters',
  confirm: 'Repeat your password',
};

export const validationSchema = Yup.object().shape({
  firstName: Yup.string()
    .min(2, 'Your name is too short.')
    .required('First name is required.'),

  lastName: Yup.string()
    .min(2, 'Lastname is too short.')
    .required('Last name is required.'),

  email: Yup.string()
    .email('Please provide a valid email address.')
    .required('Email is required.'),

  username: Yup.string()
    .matches(/[a-z0-9]/, 'Please create a username with only alphanumeric characters.')
    .required('Username is required.'),

  password: Yup.string()
    .min(8, 'Password must be at least 8 characters.')
    .matches(/[\w]/, 'At least one letter.')
    // .matches(/[\d]/, 'At least one number.')
    .required('Password is required.'),

  terms: Yup.boolean()
    .test(
      'must-accept-terms',
      'Click the box to proceed',
      (value) => value === true,
    ),
});

export const roles = [
  {
    label: 'Data Scientist',
    value: 1,
  },
  {
    label: 'Developer',
    value: 2,
  },
  {
    label: 'ML engineer',
    value: 3,
  },
  {
    label: 'Researcher',
    value: 4,
  },
  {
    label: 'Student',
    value: 5,
  },
  {
    label: 'Team lead',
    value: 6,
  },
];

export const userTypes = [
  {
    label: 'My company or team',
    value: 1,
  },
  {
    label: 'Just me',
    value: 2,
  },
];
